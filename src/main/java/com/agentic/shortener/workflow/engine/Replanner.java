package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.audit.AuditService;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.DecisionRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single replan mechanism (FR-ORC-013, FR-POL-007, ADR-0004 §6, research R10, CHK007). In ONE database
 * transaction: the cause's own record (clarification, requirement change or rework), the probe-link sweep if
 * TEST is affected, reset of the affected nodes (from-node and descendants) with their prior outputs kept in
 * STAGE_INVALIDATED, DECISION_INVALIDATED for approvals and implementation evidence on affected nodes, plan
 * version + 1, PLAN_REPLANNED and the run status change. If anything fails the transaction rolls back, the
 * prior state is unchanged, REPLAN_ABORTED is written separately and the command fails with REPLAN_FAILED.
 * The engine advances only after commit (the caller does that).
 */
@Component
public class Replanner {

    private static final Logger log = LoggerFactory.getLogger(Replanner.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final WorkflowGraph GRAPH = WorkflowGraph.standard();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final DecisionRepository decisions;
    private final WorkflowStore store;
    private final LinkService links;
    private final RunLocks locks;
    private final ObjectMapper json;

    public Replanner(JdbcTemplate jdbc, PlatformTransactionManager txManager, AuditService audit,
            DecisionRepository decisions, WorkflowStore store, LinkService links, RunLocks locks, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.audit = audit;
        this.decisions = decisions;
        this.store = store;
        this.links = links;
        this.locks = locks;
        this.json = json;
    }

    /** What caused the replan; {@code record} writes the cause's own decision and events inside the transaction. */
    public record Cause(String name, String reason, Node fromNode, Set<Node> keep, IntConsumer record) {
    }

    /** Affected = the from-node and all its descendants in the DAG, minus nodes the cause keeps. */
    public static Set<Node> affected(Node fromNode, Set<Node> keep) {
        Set<Node> affected = EnumSet.of(fromNode);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (NodeDefinition d : GRAPH.nodes()) {
                if (!affected.contains(d.node()) && d.dependsOn().stream().anyMatch(affected::contains)) {
                    affected.add(d.node());
                    grew = true;
                }
            }
        }
        affected.removeAll(keep);
        return affected;
    }

    /** Runs the replan transaction; returns the new plan version. */
    public int replan(UUID runId, Cause cause) {
        locks.assertHeld(runId);
        WorkflowRun run = store.loadRun(runId);
        int oldPlan = run.getPlanVersion();
        int newPlan = oldPlan + 1;
        Set<Node> affected = affected(cause.fromNode(), cause.keep());
        List<String> preserved = GRAPH.nodes().stream().map(NodeDefinition::node).filter(n -> !affected.contains(n))
                .map(Enum::name).toList();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("update workflow_run set plan_version = ?, status = 'RUNNING', pending_action = null, "
                        + "recoverable = null, ended_at = null, updated_at = ?, version = version + 1 where id = ?",
                        newPlan, OffsetDateTime.now(ZoneOffset.UTC), runId);
                cause.record().accept(newPlan);
                if (affected.contains(Node.TEST)) {
                    int deleted = links.deleteProbeLinks(runId); // same database, same transaction
                    if (deleted > 0) {
                        audit.append(runId, AuditEventType.COMPENSATION_COMPLETED, Node.TEST, Actor.ENGINE,
                                Map.of("deleted", deleted, "trigger", "replan"), false);
                    }
                }
                for (WorkflowStage stage : store.loadStages(runId)) {
                    if (affected.contains(stage.getNode())) {
                        invalidate(runId, stage, newPlan);
                    }
                }
                List<Long> invalidated = invalidateDecisions(runId, affected, cause.reason(), newPlan);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("oldPlanVersion", oldPlan);
                payload.put("newPlanVersion", newPlan);
                payload.put("cause", cause.name());
                payload.put("reason", cause.reason());
                payload.put("fromNode", cause.fromNode().name());
                payload.put("affected", GRAPH.nodes().stream().map(NodeDefinition::node).filter(affected::contains)
                        .map(Enum::name).toList());
                payload.put("preserved", preserved);
                payload.put("invalidatedDecisions", invalidated);
                audit.append(runId, AuditEventType.PLAN_REPLANNED, cause.fromNode(), Actor.ENGINE, payload, false);
            });
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Replan of run {} aborted", runId, e);
            tx.executeWithoutResult(status -> audit.append(runId, AuditEventType.REPLAN_ABORTED, cause.fromNode(),
                    Actor.ENGINE, Map.of("cause", cause.name(), "reason", cause.reason(), "error",
                            e.getClass().getSimpleName(), "planVersion", oldPlan), false));
            throw new ApiException(ErrorCategory.REPLAN_FAILED, HttpStatus.CONFLICT,
                    "replan failed and was rolled back; the run is unchanged at plan version " + oldPlan);
        }
        return newPlan;
    }

    private void invalidate(UUID runId, WorkflowStage stage, int newPlan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("priorStatus", stage.getStatus().name());
        payload.put("priorPlanVersion", stage.getPlanVersion());
        payload.put("priorAttempts", stage.getAttempts());
        payload.put("priorOutput", stage.getOutputJson() == null ? null : parse(stage.getOutputJson()));
        audit.append(runId, AuditEventType.STAGE_INVALIDATED, stage.getNode(), Actor.ENGINE, payload, false);
        jdbc.update("""
                update workflow_stage set status = 'PENDING', output_json = null, provenance = null, attempts = 0,
                    started_at = null, ended_at = null, thread_name = null, failure_class = null, failure_code = null,
                    failure_reason = null, plan_version = ?
                where run_id = ? and node = ?""", newPlan, runId, stage.getNode().name());
    }

    /** Valid approvals and implementation evidence on affected nodes are superseded by DECISION_INVALIDATED. */
    private List<Long> invalidateDecisions(UUID runId, Set<Node> affected, String reason, int newPlan) {
        List<Decision> lineage = decisions.findByRunIdOrderByIdAsc(runId);
        Set<Long> superseded = lineage.stream().map(Decision::getSupersedesId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<Long> invalidated = new ArrayList<>();
        for (Decision d : lineage) {
            boolean subject = (d.getType() == DecisionType.APPROVAL || d.getType() == DecisionType.IMPLEMENTATION_EVIDENCE)
                    && d.getGate() != null && affected.contains(Node.valueOf(d.getGate()));
            if (subject && !superseded.contains(d.getId())) {
                Decision invalidation = decisions.save(new Decision(runId, DecisionType.DECISION_INVALIDATED, d.getGate(),
                        ActorType.SYSTEM, Actor.ENGINE.actorIdentity(), "invalidated by replan to plan " + newPlan + ": "
                                + reason, newPlan, d.getId(), "{}", Instant.now()));
                audit.append(runId, AuditEventType.DECISION_INVALIDATED, Node.valueOf(d.getGate()), Actor.ENGINE,
                        Map.of("decisionId", invalidation.getId(), "invalidates", d.getId(), "type", d.getType().name()),
                        false);
                invalidated.add(d.getId());
            }
        }
        return invalidated;
    }

    private Map<String, Object> parse(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (Exception e) {
            return Map.of("unparsed", text);
        }
    }
}
