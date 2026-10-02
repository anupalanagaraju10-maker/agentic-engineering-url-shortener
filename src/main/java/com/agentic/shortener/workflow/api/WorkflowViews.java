package com.agentic.shortener.workflow.api;

import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.DecisionType;
import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.NodeDefinition;
import com.agentic.shortener.workflow.engine.NodeKind;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.RunStatus;
import com.agentic.shortener.workflow.engine.StageStatus;
import com.agentic.shortener.workflow.engine.WorkflowGraph;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.PolicyEvaluationRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the response views of contracts/openapi.yaml (Run, Stage, PolicyEvaluation, AuditEvent, Decision). */
@Component
public class WorkflowViews {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final WorkflowGraph GRAPH = WorkflowGraph.standard();

    private final WorkflowStore store;
    private final PolicyEvaluationRepository evaluations;
    private final AuditEventRepository events;
    private final ObjectMapper json;

    public WorkflowViews(WorkflowStore store, PolicyEvaluationRepository evaluations, AuditEventRepository events,
            ObjectMapper json) {
        this.store = store;
        this.evaluations = evaluations;
        this.events = events;
        this.json = json;
    }

    public record RunView(UUID id, String correlationId, String originalRequirement, String currentRequirement,
            String changeType, RunStatus status, String pendingAction, String statusReason, Integer planVersion,
            String policyVersion, boolean recoverable, List<StageView> stages, List<PolicyView> policyEvaluations) {
    }

    public record StageView(Node node, NodeKind kind, boolean conditional, List<Node> dependsOn, StageStatus status,
            Integer attempts, Instant startedAt, Instant endedAt, String threadName, Provenance provenance,
            FailureClass failureClass, String failureReason, Integer planVersion, Map<String, Object> output) {
    }

    public record PolicyView(String checkId, String domain, String node, Boolean mandatory, String result,
            String reason, Integer planVersion, Long resolutionDecisionId) {
    }

    public record EventView(Integer seq, AuditEventType type, Node node, ActorType actorType, String actorIdentity,
            UUID runId, String correlationId, Integer planVersion, String policyVersion, boolean injected,
            Map<String, Object> payload, Instant createdAt) {
    }

    public record DecisionView(Long id, DecisionType type, String gate, ActorType actorType, String actorIdentity,
            String reason, Integer planVersion, Long supersedesId, Map<String, Object> payload, Instant createdAt) {
    }

    public RunView run(UUID runId) {
        WorkflowRun run = store.loadRun(runId);
        Map<Node, WorkflowStage> stages = store.stageMap(runId);
        List<StageView> stageViews = GRAPH.nodes().stream().map(d -> stage(d, stages.get(d.node()))).toList();
        List<PolicyView> policies = evaluations.findByRunIdOrderByIdAsc(runId).stream()
                .map(e -> new PolicyView(e.getCheckId(), e.getDomain(), e.getNode(), e.getMandatory(), e.getResult(),
                        e.getReason(), e.getPlanVersion(), e.getResolutionDecisionId()))
                .toList();
        return new RunView(run.getId(), run.getCorrelationId(), run.getOriginalRequirement(),
                run.getCurrentRequirement(), run.getChangeType(), run.getStatus(), run.getPendingAction(),
                run.getStopReason(), run.getPlanVersion(), run.getPolicyVersion(), Boolean.TRUE.equals(run.getRecoverable()),
                stageViews, policies);
    }

    public List<EventView> events(UUID runId) {
        store.loadRun(runId);
        return events.findByRunIdOrderBySeqAsc(runId).stream().map(this::event).toList();
    }

    public List<DecisionView> decisions(UUID runId) {
        store.loadRun(runId);
        return store.decisions(runId).stream().map(this::decision).toList();
    }

    private StageView stage(NodeDefinition d, WorkflowStage s) {
        List<Node> deps = d.dependsOn().stream().sorted().toList();
        return new StageView(d.node(), d.kind(), d.conditional(), deps, s.getStatus(), s.getAttempts(), s.getStartedAt(),
                s.getEndedAt(), s.getThreadName(), s.getProvenance(), s.getFailureClass(), s.getFailureReason(),
                s.getPlanVersion(), parse(s.getOutputJson()));
    }

    private EventView event(AuditEvent e) {
        return new EventView(e.getSeq(), e.getType(), e.getNode(), e.getActorType(), e.getActorIdentity(), e.getRunId(),
                e.getCorrelationId(), e.getPlanVersion(), e.getPolicyVersion(), e.isInjected(), parse(e.getPayloadJson()),
                e.getCreatedAt());
    }

    private DecisionView decision(Decision d) {
        return new DecisionView(d.getId(), d.getType(), d.getGate(), d.getActorType(), d.getActorIdentity(),
                d.getReason(), d.getPlanVersion(), d.getSupersedesId(), parse(d.getPayloadJson()), d.getCreatedAt());
    }

    private Map<String, Object> parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored JSON", e);
        }
    }
}
