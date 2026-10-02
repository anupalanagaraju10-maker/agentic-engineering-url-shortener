package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.audit.AuditService;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.DecisionRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowRunRepository;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import com.agentic.shortener.workflow.persistence.WorkflowStageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only write path for run and stage state (ADR-0002, ADR-0003). Every state-changing method:
 * <ul>
 *   <li>requires the per-run lock to be held by the calling (coordinating) thread (H1);</li>
 *   <li>runs in its own short transaction together with its audit events (H2) — there is no
 *       command-wide transaction.</li>
 * </ul>
 */
@Component
public class WorkflowStore {

    public static final String POLICY_VERSION = "v1";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final DecisionRepository decisions;
    private final WorkflowRunRepository runs;
    private final WorkflowStageRepository stages;
    private final RunLocks locks;
    private final ObjectMapper json;

    public WorkflowStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, AuditService audit,
            DecisionRepository decisions, WorkflowRunRepository runs, WorkflowStageRepository stages, RunLocks locks,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.audit = audit;
        this.decisions = decisions;
        this.runs = runs;
        this.stages = stages;
        this.locks = locks;
        this.json = json;
    }

    // ---------------------------------------------------------------- reads

    public WorkflowRun loadRun(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new ApiException(ErrorCategory.NOT_FOUND, HttpStatus.NOT_FOUND,
                "workflow run " + runId + " not found"));
    }

    public List<WorkflowStage> loadStages(UUID runId) {
        return stages.findByRunId(runId).stream().sorted(Comparator.comparing(WorkflowStage::getNode)).toList();
    }

    public Map<Node, WorkflowStage> stageMap(UUID runId) {
        Map<Node, WorkflowStage> map = new EnumMap<>(Node.class);
        loadStages(runId).forEach(s -> map.put(s.getNode(), s));
        return map;
    }

    /** Outputs of all succeeded stages, i.e. the context available to downstream stages (FR-ORC-009). */
    public Map<Node, Map<String, Object>> outputs(UUID runId) {
        Map<Node, Map<String, Object>> outputs = new EnumMap<>(Node.class);
        for (WorkflowStage stage : loadStages(runId)) {
            if (stage.getStatus() == StageStatus.SUCCEEDED && stage.getOutputJson() != null) {
                outputs.put(stage.getNode(), fromJson(stage.getOutputJson()));
            }
        }
        return outputs;
    }

    public boolean hasBranchDecision(UUID runId, Node node, int planVersion) {
        return decisions.existsByRunIdAndTypeAndGateAndPlanVersion(runId, DecisionType.BRANCH, node.name(), planVersion);
    }

    // ---------------------------------------------------------------- run lifecycle

    /** Creates the run, its 14 PENDING stage rows and the RUN_CREATED event in one transaction. */
    public UUID createRun(String requirement, Actor submittedBy, String correlationId) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = now();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into workflow_run (id, correlation_id, original_requirement, current_requirement, status,
                        plan_version, policy_version, created_at, updated_at, version)
                    values (?, ?, ?, ?, 'RUNNING', 1, ?, ?, ?, 0)""",
                    runId, correlationId == null ? runId.toString() : correlationId, requirement, requirement,
                    POLICY_VERSION, now, now);
            for (Node node : Node.values()) {
                jdbc.update("insert into workflow_stage (run_id, node, status, attempts, plan_version) values (?, ?, 'PENDING', 0, 1)",
                        runId, node.name());
            }
            audit.append(runId, AuditEventType.RUN_CREATED, null, submittedBy, Map.of("requirement", requirement), false);
        });
        return runId;
    }

    public void completeRun(UUID runId) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.COMPLETED, null, null, true);
            audit.append(runId, AuditEventType.RUN_COMPLETED, null, Actor.ENGINE, Map.of(), false);
        });
    }

    public void failRun(UUID runId, String reason) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.FAILED, null, reason, true);
            audit.append(runId, AuditEventType.RUN_FAILED, null, Actor.ENGINE, Map.of("reason", reason), false);
        });
    }

    // ---------------------------------------------------------------- stage transitions

    /** Conditional PENDING -> RUNNING claim, committed before the stage is submitted (H2). */
    public boolean claimStage(UUID runId, Node node) {
        locks.assertHeld(runId);
        Boolean claimed = tx.execute(status -> {
            int rows = jdbc.update("""
                    update workflow_stage set status = 'RUNNING', attempts = attempts + 1, started_at = ?,
                        ended_at = null, thread_name = null, failure_class = null, failure_code = null,
                        failure_reason = null
                    where run_id = ? and node = ? and status = 'PENDING'""", now(), runId, node.name());
            if (rows == 1) {
                audit.append(runId, AuditEventType.STAGE_STARTED, node, Actor.ENGINE, Map.of(), false);
            }
            return rows == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    /** Output and SUCCEEDED are committed together (the basis of attempt rollback, ADR-0005). */
    public void completeStage(UUID runId, Node node, Map<String, Object> output, Provenance provenance,
            Instant startedAt, Instant endedAt, String threadName) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'SUCCEEDED', output_json = ?, provenance = ?, started_at = ?,
                        ended_at = ?, thread_name = ?
                    where run_id = ? and node = ? and status = 'RUNNING'""",
                    toJson(output), provenance.name(), at(startedAt), at(endedAt), threadName, runId, node.name());
            audit.append(runId, AuditEventType.STAGE_SUCCEEDED, node, Actor.ENGINE,
                    Map.of("provenance", provenance.name(), "durationMs", Duration.between(startedAt, endedAt).toMillis(),
                            "thread", threadName), false);
        });
    }

    public void failStage(UUID runId, Node node, FailureClass failureClass, String code, String reason,
            Instant startedAt, Instant endedAt, String threadName) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'FAILED', failure_class = ?, failure_code = ?, failure_reason = ?,
                        started_at = ?, ended_at = ?, thread_name = ?
                    where run_id = ? and node = ? and status = 'RUNNING'""",
                    failureClass.name(), code, truncate(reason), at(startedAt), at(endedAt), threadName, runId,
                    node.name());
            audit.append(runId, AuditEventType.STAGE_FAILED, node, Actor.ENGINE,
                    Map.of("failureClass", failureClass.name(), "code", code, "reason", String.valueOf(reason)), false);
        });
    }

    /** A stage whose entry conditions are not met never starts (FR-ORC-008): PENDING -> FAILED directly. */
    public void failStageBeforeStart(UUID runId, Node node, String code, String reason) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'FAILED', failure_class = 'PERMANENT', failure_code = ?,
                        failure_reason = ?
                    where run_id = ? and node = ? and status = 'PENDING'""", code, truncate(reason), runId, node.name());
            audit.append(runId, AuditEventType.STAGE_FAILED, node, Actor.ENGINE,
                    Map.of("failureClass", "PERMANENT", "code", code, "reason", reason, "started", false), false);
        });
    }

    /** Records the branch decision for a conditional node; a branch not taken is SKIPPED (FR-ORC-007). */
    public void recordBranch(UUID runId, Node node, boolean taken, int planVersion, String condition) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            String reason = (taken ? "taken: " : "not taken: ") + condition + (taken ? " (true)" : " (false)");
            decisions.save(new Decision(runId, DecisionType.BRANCH, node.name(), ActorType.SYSTEM,
                    Actor.ENGINE.actorIdentity(), reason, planVersion, null, toJson(Map.of("taken", taken)),
                    Instant.now()));
            audit.append(runId, AuditEventType.BRANCH_TAKEN, node, Actor.ENGINE,
                    Map.of("taken", taken, "condition", condition), false);
            if (!taken) {
                jdbc.update("update workflow_stage set status = 'SKIPPED' where run_id = ? and node = ? and status = 'PENDING'",
                        runId, node.name());
                audit.append(runId, AuditEventType.STAGE_SKIPPED, node, Actor.ENGINE, Map.of("reason", reason), false);
            }
        });
    }

    /** A HUMAN_GATE or EXTERNAL_ACTION node becomes BLOCKED and the run waits (FR-HUM-001, ADR-0003). */
    public void blockNode(UUID runId, NodeDefinition definition) {
        locks.assertHeld(runId);
        Node node = definition.node();
        RunStatus waiting;
        String pendingAction;
        AuditEventType event;
        if (definition.kind() == NodeKind.EXTERNAL_ACTION) {
            waiting = RunStatus.AWAITING_IMPLEMENTATION;
            pendingAction = "RECORD_IMPLEMENTATION";
            event = AuditEventType.IMPLEMENTATION_REQUESTED;
        } else if (node == Node.CLARIFICATION) {
            waiting = RunStatus.AWAITING_CLARIFICATION;
            pendingAction = "CLARIFY";
            event = AuditEventType.CLARIFICATION_REQUESTED;
        } else {
            waiting = RunStatus.AWAITING_APPROVAL;
            pendingAction = "APPROVE:" + node.name();
            event = AuditEventType.APPROVAL_REQUESTED;
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("update workflow_stage set status = 'BLOCKED' where run_id = ? and node = ? and status = 'PENDING'",
                    runId, node.name());
            updateRun(runId, waiting, pendingAction, null, false);
            audit.append(runId, event, node, Actor.ENGINE, Map.of("pendingAction", pendingAction), false);
        });
    }

    /** BLOCKED -> SUCCEEDED once the human decision / external evidence has been validated by the caller. */
    public void resolveBlocked(UUID runId, Node node, Map<String, Object> output, Provenance provenance) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> resolveBlockedInTransaction(runId, node, output, provenance));
    }

    public List<Decision> decisions(UUID runId) {
        return decisions.findByRunIdOrderByIdAsc(runId);
    }

    // ---------------------------------------------------------------- derived state, policy, safe-stop

    /** After UNDERSTAND: normalized requirement + change type on the run, with their audit events. */
    public void recordUnderstanding(UUID runId, Map<String, Object> understand) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("update workflow_run set normalized_json = ?, change_type = ?, updated_at = ?, version = version + 1 where id = ?",
                    toJson(understand), String.valueOf(understand.get("changeType")), now(), runId);
            audit.append(runId, AuditEventType.REQUIREMENT_NORMALIZED, Node.UNDERSTAND, Actor.ENGINE,
                    Map.of("normalized", understand.get("normalized"), "capabilities", understand.get("capabilities"),
                            "changeType", understand.get("changeType")), false);
            Object findings = understand.get("findings");
            if (findings instanceof List<?> list && !list.isEmpty()) {
                audit.append(runId, AuditEventType.AMBIGUITY_DETECTED, Node.UNDERSTAND, Actor.ENGINE,
                        Map.of("findings", findings), false);
            }
        });
    }

    public void recordPolicyEvaluation(UUID runId, String policyVersion, String checkId, String domain, Node node,
            boolean mandatory, String result, String reason, int planVersion) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into policy_evaluation (run_id, policy_version, check_id, domain, node, mandatory, result,
                        reason, plan_version, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    runId, policyVersion, checkId, domain, node.name(), mandatory, result, reason, planVersion, now());
            audit.append(runId, AuditEventType.POLICY_EVALUATED, node, Actor.ENGINE,
                    Map.of("checkId", checkId, "result", result, "reason", reason, "mandatory", mandatory), false);
        });
    }

    /** SAFE_STOPPED with the recoverable flag (FR-REL-007/008). Non-recoverable stops are final. */
    public void safeStop(UUID runId, String reason, boolean recoverable) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.SAFE_STOPPED, recoverable ? "RESUME" : null, reason, !recoverable);
            jdbc.update("update workflow_run set recoverable = ? where id = ?", recoverable, runId);
            audit.append(runId, AuditEventType.SAFE_STOPPED, null, Actor.ENGINE,
                    Map.of("reason", reason, "recoverable", recoverable), false);
        });
    }

    /**
     * IMPLEMENTATION_DEFECT after accepted evidence (CHK036): the failed stages stay FAILED, succeeded branches
     * are kept, and the run waits for a HUMAN rework or terminate decision.
     */
    public void awaitRework(UUID runId, String reason, List<String> nodes) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.AWAITING_REWORK, "REWORK_OR_TERMINATE", reason, false);
            audit.append(runId, AuditEventType.RECOVERY_STARTED, null, Actor.ENGINE,
                    Map.of("mechanism", "REWORK", "cause", "IMPLEMENTATION_DEFECT", "nodes", nodes), false);
        });
    }

    /** The run waits for a HUMAN policy-exception decision (research R11; the evaluated node already succeeded). */
    public void waitForExceptionDecision(UUID runId, String pendingAction, Map<String, Object> payload) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.AWAITING_APPROVAL, pendingAction, null, false);
            audit.append(runId, AuditEventType.EXCEPTION_REQUESTED, null, Actor.ENGINE, payload, false);
        });
    }

    // ---------------------------------------------------------------- human decisions (FR-HUM, ADR-0004)

    /** A refused command is itself recorded, by the engine, with what was attempted (FR-HUM-007). */
    public void refuseDecision(UUID runId, String action, Actor attempted, String reason, int planVersion) {
        locks.assertHeld(runId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("attemptedActorType", attempted == null || attempted.actorType() == null ? null
                : attempted.actorType().name());
        payload.put("attemptedActorIdentity", attempted == null ? null : attempted.actorIdentity());
        tx.executeWithoutResult(status -> {
            decisions.save(new Decision(runId, DecisionType.DECISION_REFUSED, null, ActorType.SYSTEM,
                    Actor.ENGINE.actorIdentity(), truncate(reason), planVersion, null, toJson(payload), Instant.now()));
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("reason", reason);
            audit.append(runId, AuditEventType.DECISION_REFUSED, null, Actor.ENGINE, event, false);
        });
    }

    /** Records the APPROVAL decision and completes the BLOCKED gate in one transaction. */
    public Decision approveGate(UUID runId, Node gate, Actor actor, String reason, int planVersion,
            List<String> acceptedRisks) {
        locks.assertHeld(runId);
        return tx.execute(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.APPROVAL, gate.name(), actor.actorType(),
                    actor.actorIdentity(), reason, planVersion, null,
                    toJson(Map.of("acceptedRisks", acceptedRisks == null ? List.of() : acceptedRisks)), Instant.now()));
            audit.append(runId, AuditEventType.APPROVAL_GRANTED, gate, actor,
                    Map.of("decisionId", decision.getId(), "reason", reason), false);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("decisionId", decision.getId());
            output.put("approvedBy", actor.actorIdentity());
            output.put("acceptedRisks", acceptedRisks == null ? List.of() : acceptedRisks);
            resolveBlockedInTransaction(runId, gate, output, Provenance.EXTERNAL);
            return decision;
        });
    }

    /** REJECTION: the gate stage FAILED (code REJECTED) and the run waits for rework or termination. */
    public void rejectGate(UUID runId, Node gate, Actor actor, String reason, int planVersion) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.REJECTION, gate.name(), actor.actorType(),
                    actor.actorIdentity(), reason, planVersion, null, toJson(Map.of()), Instant.now()));
            jdbc.update("""
                    update workflow_stage set status = 'FAILED', failure_class = 'PERMANENT', failure_code = 'REJECTED',
                        failure_reason = ?, ended_at = ?
                    where run_id = ? and node = ? and status = 'BLOCKED'""", truncate(reason), now(), runId, gate.name());
            updateRun(runId, RunStatus.AWAITING_REWORK, "REWORK_OR_TERMINATE", null, false);
            audit.append(runId, AuditEventType.APPROVAL_REJECTED, gate, actor,
                    Map.of("decisionId", decision.getId(), "reason", reason), false);
        });
    }

    /** TERMINATION by a HUMAN: the run ends FAILED (compensation is added in Phase 6). */
    public void terminateRun(UUID runId, Actor actor, String reason, int planVersion) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.TERMINATION, null, actor.actorType(),
                    actor.actorIdentity(), reason, planVersion, null, toJson(Map.of()), Instant.now()));
            updateRun(runId, RunStatus.FAILED, null, "terminated by " + actor.actorIdentity() + ": " + reason, true);
            audit.append(runId, AuditEventType.RUN_FAILED, null, actor,
                    Map.of("decisionId", decision.getId(), "reason", "terminated: " + reason), false);
        });
    }

    /** IMPLEMENTATION_EVIDENCE decision + IMPLEMENT completed with provenance EXTERNAL (ADR-0003 §8). */
    public Decision recordImplementation(UUID runId, Actor actor, String summary, int planVersion,
            Map<String, Object> evidence) {
        locks.assertHeld(runId);
        return tx.execute(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.IMPLEMENTATION_EVIDENCE,
                    Node.IMPLEMENT.name(), actor.actorType(), actor.actorIdentity(), truncate(summary), planVersion, null,
                    toJson(evidence), Instant.now()));
            audit.append(runId, AuditEventType.IMPLEMENTATION_RECORDED, Node.IMPLEMENT, actor,
                    Map.of("decisionId", decision.getId(), "revision", String.valueOf(evidence.get("revision"))), false);
            Map<String, Object> output = new LinkedHashMap<>(evidence);
            output.put("decisionId", decision.getId());
            resolveBlockedInTransaction(runId, Node.IMPLEMENT, output, Provenance.EXTERNAL);
            return decision;
        });
    }

    // ---------------------------------------------------------------- helpers

    private void resolveBlockedInTransaction(UUID runId, Node node, Map<String, Object> output, Provenance provenance) {
        int rows = jdbc.update("""
                update workflow_stage set status = 'SUCCEEDED', output_json = ?, provenance = ?, ended_at = ?
                where run_id = ? and node = ? and status = 'BLOCKED'""",
                toJson(output), provenance.name(), now(), runId, node.name());
        if (rows != 1) {
            throw new ApiException(ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                    "run " + runId + " is not waiting at " + node);
        }
        updateRun(runId, RunStatus.RUNNING, null, null, false);
        audit.append(runId, AuditEventType.STAGE_SUCCEEDED, node, Actor.ENGINE, Map.of("provenance", provenance.name()),
                false);
    }

    private void updateRun(UUID runId, RunStatus status, String pendingAction, String stopReason, boolean ended) {
        OffsetDateTime now = now();
        jdbc.update("""
                update workflow_run set status = ?, pending_action = ?, stop_reason = coalesce(?, stop_reason),
                    updated_at = ?, ended_at = ?, version = version + 1
                where id = ?""", status.name(), pendingAction, truncate(stopReason), now, ended ? now : null, runId);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : new LinkedHashMap<>(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("stage output is not serializable", e);
        }
    }

    private Map<String, Object> fromJson(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt stage output", e);
        }
    }
}
