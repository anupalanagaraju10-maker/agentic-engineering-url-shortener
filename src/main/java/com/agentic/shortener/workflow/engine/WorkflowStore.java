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
        tx.executeWithoutResult(status -> {
            int rows = jdbc.update("""
                    update workflow_stage set status = 'SUCCEEDED', output_json = ?, provenance = ?, ended_at = ?
                    where run_id = ? and node = ? and status = 'BLOCKED'""",
                    toJson(output), provenance.name(), now(), runId, node.name());
            if (rows != 1) {
                throw new ApiException(ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                        "run " + runId + " is not waiting at " + node);
            }
            updateRun(runId, RunStatus.RUNNING, null, null, false);
            audit.append(runId, AuditEventType.STAGE_SUCCEEDED, node, Actor.ENGINE,
                    Map.of("provenance", provenance.name()), false);
        });
    }

    // ---------------------------------------------------------------- helpers

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
