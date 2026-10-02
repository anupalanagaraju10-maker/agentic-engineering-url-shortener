package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.engine.StageStatus;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.PolicyEvaluationRepository;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.DecisionRow;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.EventRow;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.Input;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.PolicyRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * RELEASE_READINESS, the join of the parallel group: reads the persisted run state (read-only; all writes stay
 * with the engine, H1) and applies {@link ReleaseReadiness}. Exit condition: the run is ready. A successful
 * readiness carries AUD-01 PASS, which the policy evaluator records.
 */
@Component
public class ReleaseReadinessExecutor implements StageExecutor {

    private final WorkflowStore store;
    private final PolicyEvaluationRepository policies;
    private final AuditEventRepository events;
    private final LinkService links;
    private final ObjectMapper json;

    public ReleaseReadinessExecutor(WorkflowStore store, PolicyEvaluationRepository policies,
            AuditEventRepository events, LinkService links, ObjectMapper json) {
        this.store = store;
        this.policies = policies;
        this.events = events;
        this.links = links;
        this.json = json;
    }

    @Override
    public Node node() {
        return Node.RELEASE_READINESS;
    }

    @Override
    public StageResult execute(StageContext context) {
        return StageResult.success(ReleaseReadiness.evaluate(input(context)), Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        return Boolean.TRUE.equals(output.get("ready")) ? Optional.empty()
                : Optional.of("release readiness blocked: " + output.get("blockers"));
    }

    Input input(StageContext context) {
        UUID runId = context.runId();
        Map<Node, StageStatus> stages = new EnumMap<>(Node.class);
        for (WorkflowStage s : store.loadStages(runId)) {
            stages.put(s.getNode(), s.getStatus());
        }
        List<PolicyRow> policyRows = policies.findByRunIdOrderByIdAsc(runId).stream()
                .map(p -> new PolicyRow(p.getCheckId(), Boolean.TRUE.equals(p.getMandatory()), p.getResult(),
                        p.getResolutionDecisionId(), p.getPlanVersion()))
                .toList();
        List<DecisionRow> decisionRows = store.decisions(runId).stream()
                .map(d -> new DecisionRow(d.getId(), d.getType(), d.getGate(), d.getPlanVersion())).toList();
        List<EventRow> eventRows = events.findByRunIdOrderBySeqAsc(runId).stream().map(this::row).toList();
        return new Input(context.planVersion(), stages, context.upstreamOutputs(), policyRows, decisionRows, eventRows,
                links.countProbeLinks(runId));
    }

    private EventRow row(AuditEvent e) {
        return new EventRow(e.getSeq(), e.getType(), e.getNode(), decisionId(e.getPayloadJson()));
    }

    private Long decisionId(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            JsonNode id = json.readTree(payload).get("decisionId");
            return id == null || !id.canConvertToLong() ? null : id.asLong();
        } catch (Exception e) {
            return null;
        }
    }
}
