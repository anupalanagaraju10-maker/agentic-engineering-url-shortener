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
        return createRun(requirement, submittedBy, correlationId, null);
    }

    /** {@code faultPlanJson} non-null marks an injected run (FR-REL-011, CHK038). */
    public UUID createRun(String requirement, Actor submittedBy, String correlationId, String faultPlanJson) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = now();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into workflow_run (id, correlation_id, original_requirement, current_requirement, status,
                        plan_version, policy_version, fault_plan_json, created_at, updated_at, version)
                    values (?, ?, ?, ?, 'RUNNING', 1, ?, ?, ?, ?, 0)""",
                    runId, correlationId == null ? runId.toString() : correlationId, requirement, requirement,
                    POLICY_VERSION, faultPlanJson, now, now);
            for (Node node : Node.values()) {
                jdbc.update("insert into workflow_stage (run_id, node, status, attempts, plan_version) values (?, ?, 'PENDING', 0, 1)",
                        runId, node.name());
            }
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("requirement", requirement);
            created.put("faultInjected", faultPlanJson != null);
            audit.append(runId, AuditEventType.RUN_CREATED, null, submittedBy, created, false);
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
        completeStage(runId, node, output, provenance, startedAt, endedAt, threadName, false);
    }

    public void completeStage(UUID runId, Node node, Map<String, Object> output, Provenance provenance,
            Instant startedAt, Instant endedAt, String threadName, boolean injected) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'SUCCEEDED', output_json = ?, provenance = ?, started_at = ?,
                        ended_at = ?, thread_name = ?
                    where run_id = ? and node = ? and status = 'RUNNING'""",
                    toJson(output), provenance.name(), at(startedAt), at(endedAt), threadName, runId, node.name());
            audit.append(runId, AuditEventType.STAGE_SUCCEEDED, node, Actor.ENGINE,
                    Map.of("provenance", provenance.name(), "durationMs", Duration.between(startedAt, endedAt).toMillis(),
                            "thread", threadName), injected);
        });
    }

    public void failStage(UUID runId, Node node, FailureClass failureClass, String code, String reason,
            Instant startedAt, Instant endedAt, String threadName) {
        failStage(runId, node, failureClass, code, reason, startedAt, endedAt, threadName, false);
    }

    public void failStage(UUID runId, Node node, FailureClass failureClass, String code, String reason,
            Instant startedAt, Instant endedAt, String threadName, boolean injected) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'FAILED', failure_class = ?, failure_code = ?, failure_reason = ?,
                        started_at = ?, ended_at = ?, thread_name = ?
                    where run_id = ? and node = ? and status = 'RUNNING'""",
                    failureClass.name(), code, truncate(reason), at(startedAt), at(endedAt), threadName, runId,
                    node.name());
            audit.append(runId, AuditEventType.STAGE_FAILED, node, Actor.ENGINE,
                    Map.of("failureClass", failureClass.name(), "code", code, "reason", String.valueOf(reason)), injected);
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
    public void awaitRework(UUID runId, String reason, Map<Node, Integer> defectIncidents) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            updateRun(runId, RunStatus.AWAITING_REWORK, "REWORK_OR_TERMINATE", reason, false);
            defectIncidents.forEach((node, incident) -> audit.append(runId, AuditEventType.RECOVERY_STARTED, node,
                    Actor.ENGINE, incidentPayload(incident, node, "mechanism", "REWORK", "cause", "IMPLEMENTATION_DEFECT"),
                    false));
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

    // ---------------------------------------------------------------- replanning causes and policy exceptions

    /**
     * Clarification (inside the replan transaction): CLARIFICATION decision and event; the gate is kept
     * SUCCEEDED holding the answer (research R10); the current requirement gains the clarification.
     */
    public void recordClarification(UUID runId, Actor actor, String reason, String clarification, int newPlan) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.CLARIFICATION, Node.CLARIFICATION.name(),
                    actor.actorType(), actor.actorIdentity(), reason, newPlan, null,
                    toJson(Map.of("clarification", clarification)), Instant.now()));
            audit.append(runId, AuditEventType.CLARIFICATION_RECEIVED, Node.CLARIFICATION, actor,
                    Map.of("decisionId", decision.getId(), "clarification", clarification), false);
            jdbc.update("""
                    update workflow_stage set status = 'SUCCEEDED', output_json = ?, provenance = 'EXTERNAL', ended_at = ?,
                        plan_version = ?
                    where run_id = ? and node = 'CLARIFICATION'""",
                    toJson(Map.of("decisionId", decision.getId(), "clarification", clarification)), now(), newPlan, runId);
            jdbc.update("update workflow_run set current_requirement = concat(current_requirement, ?) where id = ?",
                    " Clarification: " + clarification, runId);
            audit.append(runId, AuditEventType.STAGE_SUCCEEDED, Node.CLARIFICATION, Actor.ENGINE,
                    Map.of("provenance", Provenance.EXTERNAL.name(), "decisionId", decision.getId()), false);
        });
    }

    /** Requirement change (inside the replan transaction): REQUIREMENT_CHANGE decision; new current requirement. */
    public void recordRequirementChange(UUID runId, Actor actor, String reason, String requirement, int newPlan) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            String previous = loadRun(runId).getCurrentRequirement();
            decisions.save(new Decision(runId, DecisionType.REQUIREMENT_CHANGE, null, actor.actorType(),
                    actor.actorIdentity(), reason, newPlan, null,
                    toJson(Map.of("previous", previous, "requirement", requirement)), Instant.now()));
            jdbc.update("update workflow_run set current_requirement = ? where id = ?", requirement, runId);
        });
    }

    /** Rework (inside the replan transaction): REWORK decision naming the node the run restarts from. */
    public void recordRework(UUID runId, Actor actor, String reason, Node fromNode, int newPlan) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> decisions.save(new Decision(runId, DecisionType.REWORK, fromNode.name(),
                actor.actorType(), actor.actorIdentity(), reason, newPlan, null, toJson(Map.of("fromNode", fromNode.name())),
                Instant.now())));
    }

    /** A replanned UNDERSTAND still reports ambiguity: the kept CLARIFICATION gate opens another round. */
    public void reopenClarification(UUID runId) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            int rows = jdbc.update("""
                    update workflow_stage set status = 'PENDING', output_json = null, provenance = null, ended_at = null
                    where run_id = ? and node = 'CLARIFICATION' and status = 'SUCCEEDED'""", runId);
            if (rows == 1) {
                audit.append(runId, AuditEventType.STAGE_INVALIDATED, Node.CLARIFICATION, Actor.ENGINE,
                        Map.of("reason", "UNDERSTAND still reports material ambiguity: another clarification round"),
                        false);
            }
        });
    }

    /**
     * HUMAN policy-exception decision (FR-POL-004/005). APPROVE records every FR-POL-005 field, resolves the
     * EXCEPTION_REQUESTED row and lets the run continue; REJECT resolves it and safe-stops (non-recoverable).
     */
    public Decision decideException(UUID runId, String checkId, Actor actor, String reason, boolean approve,
            Map<String, Object> record, int planVersion) {
        locks.assertHeld(runId);
        return tx.execute(status -> {
            Map<String, Object> payload = new LinkedHashMap<>(record);
            payload.put("policyId", checkId);
            payload.put("approvedAt", Instant.now().toString());
            Decision decision = decisions.save(new Decision(runId, approve ? DecisionType.EXCEPTION_APPROVED
                    : DecisionType.EXCEPTION_REJECTED, checkId, actor.actorType(), actor.actorIdentity(), reason,
                    planVersion, null, toJson(payload), Instant.now()));
            jdbc.update("""
                    update policy_evaluation set resolution_decision_id = ?
                    where run_id = ? and check_id = ? and plan_version = ? and result = 'EXCEPTION_REQUESTED'
                        and resolution_decision_id is null""", decision.getId(), runId, checkId, planVersion);
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("decisionId", decision.getId());
            event.put("reason", reason);
            audit.append(runId, approve ? AuditEventType.EXCEPTION_APPROVED : AuditEventType.EXCEPTION_REJECTED, null,
                    actor, event, false);
            if (approve) {
                updateRun(runId, RunStatus.RUNNING, null, null, false);
            }
            return decision;
        });
    }

    // ---------------------------------------------------------------- reliability (ADR-0005)

    public int attempts(UUID runId, Node node) {
        Integer attempts = jdbc.queryForObject("select attempts from workflow_stage where run_id = ? and node = ?",
                Integer.class, runId, node.name());
        return attempts == null ? 0 : attempts;
    }

    public List<UUID> runIdsWithStatus(RunStatus status) {
        return jdbc.queryForList("select id from workflow_run where status = ?", UUID.class, status.name());
    }

    /**
     * Attempt rollback (ADR-0005 §5): the failed or timed-out attempt commits nothing; the stage returns to
     * PENDING with no output and keeps its attempt count. Records STAGE_FAILED or STAGE_TIMED_OUT, then
     * ATTEMPT_ROLLED_BACK.
     */
    public void rollbackAttempt(UUID runId, Node node, int attempt, FailureClass failureClass, String code,
            String reason, boolean timedOut, Instant startedAt, Instant endedAt, String threadName, boolean injected) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'PENDING', output_json = null, provenance = null, started_at = ?,
                        ended_at = ?, thread_name = ?, failure_class = ?, failure_code = ?, failure_reason = ?
                    where run_id = ? and node = ? and status = 'RUNNING'""",
                    at(startedAt), at(endedAt), threadName, failureClass.name(), code, truncate(reason), runId,
                    node.name());
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("attempt", attempt);
            failure.put("failureClass", failureClass.name());
            failure.put("code", code);
            failure.put("reason", String.valueOf(reason));
            audit.append(runId, timedOut ? AuditEventType.STAGE_TIMED_OUT : AuditEventType.STAGE_FAILED, node,
                    Actor.ENGINE, failure, injected);
            audit.append(runId, AuditEventType.ATTEMPT_ROLLED_BACK, node, Actor.ENGINE,
                    Map.of("attempt", attempt, "restoredStatus", "PENDING"), injected);
        });
    }

    /** At startup: a stage left RUNNING by a crash is rolled back the same way (cause INTERRUPTED, CHK033). */
    public void rollbackInterrupted(UUID runId, Node node) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    update workflow_stage set status = 'PENDING', output_json = null, provenance = null,
                        failure_class = 'TRANSIENT', failure_code = 'INTERRUPTED', failure_reason = 'process restarted'
                    where run_id = ? and node = ? and status = 'RUNNING'""", runId, node.name());
            audit.append(runId, AuditEventType.ATTEMPT_ROLLED_BACK, node, Actor.ENGINE,
                    Map.of("restoredStatus", "PENDING", "cause", "INTERRUPTED"), false);
        });
    }

    public void retryScheduled(UUID runId, Node node, int nextAttempt, long backoffMs, int incidentId, boolean injected) {
        appendLocked(runId, AuditEventType.RETRY_SCHEDULED, node,
                incidentPayload(incidentId, node, "nextAttempt", nextAttempt, "backoffMs", backoffMs), injected);
    }

    public void retryExhausted(UUID runId, Node node, int attempts, int incidentId, boolean injected) {
        appendLocked(runId, AuditEventType.RETRY_EXHAUSTED, node, incidentPayload(incidentId, node, "attempts", attempts),
                injected);
    }

    public void fallbackUsed(UUID runId, Node node, int incidentId, boolean injected) {
        appendLocked(runId, AuditEventType.FALLBACK_USED, node,
                incidentPayload(incidentId, node, "provenance", Provenance.FALLBACK.name()), injected);
    }

    /** Opens a recovery incident; its audit seq is the incident id (ADR-0005 §11). */
    public int failureDetected(UUID runId, Node node, FailureClass failureClass, String code, boolean injected) {
        locks.assertHeld(runId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node", node == null ? null : node.name());
        payload.put("failureClass", failureClass == null ? null : failureClass.name());
        payload.put("code", code);
        Integer seq = tx.execute(status -> audit.append(runId, AuditEventType.FAILURE_DETECTED, node, Actor.ENGINE,
                payload, injected).getSeq());
        return seq == null ? 0 : seq;
    }

    public void recoveryStarted(UUID runId, Node node, int incidentId, String mechanism, boolean injected) {
        appendLocked(runId, AuditEventType.RECOVERY_STARTED, node, incidentPayload(incidentId, node, "mechanism", mechanism),
                injected);
    }

    /** Closes an incident as recovered, with its duration and recovery mechanism (the MTTR input). */
    public void recoveryCompleted(UUID runId, Node node, int incidentId, boolean injected) {
        appendLocked(runId, AuditEventType.RECOVERY_COMPLETED, node, incidentPayload(incidentId, node,
                "mechanism", lastMechanism(runId, incidentId), "durationMs", durationSince(runId, incidentId)), injected);
    }

    /** Closes every open incident as unrecovered (run FAILED or non-recoverable SAFE_STOPPED). */
    public void recoveryFailedForOpenIncidents(UUID runId, String outcome) {
        for (Map.Entry<Integer, Node> incident : openIncidents(runId).entrySet()) {
            appendLocked(runId, AuditEventType.RECOVERY_FAILED, incident.getValue(), incidentPayload(incident.getKey(),
                    incident.getValue(), "outcome", outcome, "durationMs", durationSince(runId, incident.getKey())), false);
        }
    }

    /** Open incidents: FAILURE_DETECTED events not yet closed by RECOVERY_COMPLETED or RECOVERY_FAILED. */
    public Map<Integer, Node> openIncidents(UUID runId) {
        Map<Integer, Node> open = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select seq, type, node, payload_json from audit_event
                where run_id = ? and type in ('FAILURE_DETECTED', 'RECOVERY_COMPLETED', 'RECOVERY_FAILED')
                order by seq""", runId)) {
            int seq = ((Number) row.get("SEQ")).intValue();
            if ("FAILURE_DETECTED".equals(row.get("TYPE"))) {
                Object node = row.get("NODE");
                open.put(seq, node == null ? null : Node.valueOf(node.toString()));
            } else {
                Object incident = fromJson(clob(row.get("PAYLOAD_JSON"))).get("incidentId");
                if (incident instanceof Number n) {
                    open.remove(n.intValue());
                }
            }
        }
        return open;
    }

    public Integer openIncidentFor(UUID runId, Node node) {
        return openIncidents(runId).entrySet().stream().filter(e -> e.getValue() == node).map(Map.Entry::getKey)
                .reduce((a, b) -> b).orElse(null);
    }

    public void compensationStarted(UUID runId, String trigger, Integer incidentId, boolean injected) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("trigger", trigger);
            payload.put("incidentId", incidentId);
            audit.append(runId, AuditEventType.COMPENSATION_STARTED, Node.TEST, Actor.ENGINE, payload, injected);
            if (incidentId != null) {
                audit.append(runId, AuditEventType.RECOVERY_STARTED, Node.TEST, Actor.ENGINE,
                        incidentPayload(incidentId, Node.TEST, "mechanism", "COMPENSATION"), injected);
            }
        });
    }

    public void compensationCompleted(UUID runId, int deleted, Integer incidentId, boolean injected) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deleted", deleted);
        payload.put("incidentId", incidentId);
        appendLocked(runId, AuditEventType.COMPENSATION_COMPLETED, Node.TEST, payload, injected);
    }

    public void compensationFailed(UUID runId, String reason, Integer incidentId, boolean injected) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("incidentId", incidentId);
        appendLocked(runId, AuditEventType.COMPENSATION_FAILED, Node.TEST, payload, injected);
    }

    /**
     * RESUME by a HUMAN (FR-REL-009): RESUME decision, RUN_RESUMED and RECOVERY_STARTED(RESUME) for every open
     * incident; the run is RUNNING again. Succeeded stages are untouched; rolled-back stages are PENDING.
     */
    public void resumeRun(UUID runId, Actor actor, String reason, int planVersion) {
        locks.assertHeld(runId);
        Map<Integer, Node> open = openIncidents(runId);
        tx.executeWithoutResult(status -> {
            Decision decision = decisions.save(new Decision(runId, DecisionType.RESUME, null, actor.actorType(),
                    actor.actorIdentity(), reason, planVersion, null, toJson(Map.of()), Instant.now()));
            jdbc.update("update workflow_stage set status = 'PENDING' where run_id = ? and status = 'FAILED'", runId);
            updateRun(runId, RunStatus.RUNNING, null, null, false);
            jdbc.update("update workflow_run set recoverable = null where id = ?", runId);
            audit.append(runId, AuditEventType.RUN_RESUMED, null, actor,
                    Map.of("decisionId", decision.getId(), "reason", reason), false);
            open.forEach((incident, node) -> audit.append(runId, AuditEventType.RECOVERY_STARTED, node, actor,
                    incidentPayload(incident, node, "mechanism", "RESUME"), false));
        });
        // an incident without a stage (an interrupted orchestration boundary) is recovered by the resume itself
        open.entrySet().stream().filter(e -> e.getValue() == null)
                .forEach(e -> recoveryCompleted(runId, null, e.getKey(), false));
    }

    private void appendLocked(UUID runId, AuditEventType type, Node node, Map<String, Object> payload, boolean injected) {
        locks.assertHeld(runId);
        tx.executeWithoutResult(status -> audit.append(runId, type, node, Actor.ENGINE, payload, injected));
    }

    private static Map<String, Object> incidentPayload(Integer incidentId, Node node, Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incidentId);
        payload.put("node", node == null ? null : node.name());
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    private long durationSince(UUID runId, int incidentId) {
        List<OffsetDateTime> opened = jdbc.queryForList(
                "select created_at from audit_event where run_id = ? and seq = ?", OffsetDateTime.class, runId, incidentId);
        return opened.isEmpty() ? 0 : Math.max(0, Duration.between(opened.get(0).toInstant(), Instant.now()).toMillis());
    }

    private String lastMechanism(UUID runId, int incidentId) {
        String mechanism = null;
        for (Map<String, Object> row : jdbc.queryForList(
                "select payload_json from audit_event where run_id = ? and type = 'RECOVERY_STARTED' order by seq", runId)) {
            Map<String, Object> p = fromJson(clob(row.get("PAYLOAD_JSON")));
            if (p.get("incidentId") instanceof Number n && n.intValue() == incidentId
                    && !"COMPENSATION".equals(p.get("mechanism"))) {
                mechanism = String.valueOf(p.get("mechanism"));
            }
        }
        return mechanism;
    }

    private static String clob(Object value) {
        if (value instanceof java.sql.Clob c) {
            try {
                return c.getSubString(1, (int) c.length());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return value == null ? "{}" : value.toString();
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
