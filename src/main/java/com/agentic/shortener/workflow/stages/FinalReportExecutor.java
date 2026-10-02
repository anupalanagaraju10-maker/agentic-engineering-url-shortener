package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.DecisionType;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.PolicyEvaluation;
import com.agentic.shortener.workflow.persistence.PolicyEvaluationRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FINAL_REPORT: the engineering report of the run, built only from persisted records (FR-OBS-006, NFR-006).
 * Every section cites the audit sequence numbers that support it. Idempotent: the report never includes
 * FINAL_REPORT's own events, the run's final status or wall-clock time, so regenerating it from the same
 * records yields the same report.
 */
@Component
public class FinalReportExecutor implements StageExecutor {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final List<Node> PARALLEL = List.of(Node.TEST, Node.DOCS, Node.SECURITY);

    private final WorkflowStore store;
    private final PolicyEvaluationRepository policies;
    private final AuditEventRepository events;
    private final ObjectMapper json;

    public FinalReportExecutor(WorkflowStore store, PolicyEvaluationRepository policies, AuditEventRepository events,
            ObjectMapper json) {
        this.store = store;
        this.policies = policies;
        this.events = events;
        this.json = json;
    }

    @Override
    public Node node() {
        return Node.FINAL_REPORT;
    }

    @Override
    public StageResult execute(StageContext context) {
        UUID runId = context.runId();
        WorkflowRun run = store.loadRun(runId);
        Map<Node, Map<String, Object>> outputs = context.upstreamOutputs();
        List<WorkflowStage> stages = store.loadStages(runId).stream().filter(s -> s.getNode() != Node.FINAL_REPORT)
                .sorted((a, b) -> a.getNode().compareTo(b.getNode())).toList();
        List<Decision> decisions = store.decisions(runId);
        List<AuditEvent> trail = events.findByRunIdOrderBySeqAsc(runId).stream()
                .filter(e -> e.getNode() != Node.FINAL_REPORT && e.getType() != AuditEventType.RUN_COMPLETED).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId.toString());
        report.put("outcome", "RELEASE_APPROVED");
        report.put("planVersion", run.getPlanVersion());
        report.put("policyVersion", run.getPolicyVersion());

        Map<String, Object> understand = outputs.getOrDefault(Node.UNDERSTAND, Map.of());
        Map<String, Object> design = outputs.getOrDefault(Node.DESIGN, Map.of());
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("original", run.getOriginalRequirement());
        requirement.put("current", run.getCurrentRequirement());
        requirement.put("normalized", understand.get("normalized"));
        requirement.put("changeType", understand.get("changeType"));
        requirement.put("capabilities", understand.get("capabilities"));
        requirement.put("requirementIds", design.get("requirementIds"));
        report.put("requirement", requirement);
        report.put("tasks", outputs.getOrDefault(Node.DECOMPOSE, Map.of()).get("tasks"));
        report.put("design", pick(design, "components", "interfaceChanges", "dataChanges", "testPlan",
                "securitySensitive", "implementationRequired"));

        Map<String, Object> evidence = outputs.getOrDefault(Node.IMPLEMENT, Map.of());
        Map<String, Object> implementation = pick(evidence, "decisionId", "revision", "summary", "changedArtifacts",
                "coveredComponents", "uncoveredComponents");
        decisions.stream().filter(d -> d.getType() == DecisionType.IMPLEMENTATION_EVIDENCE).reduce((a, b) -> b)
                .ifPresent(d -> implementation.put("recordedBy", d.getActorType() + "/" + d.getActorIdentity()));
        report.put("implementation", implementation);

        Map<String, Object> validation = new LinkedHashMap<>();
        List<?> probes = (List<?>) outputs.getOrDefault(Node.TEST, Map.of()).getOrDefault("probes", List.of());
        validation.put("testProbesPassed", probes.stream().filter(p -> Boolean.TRUE.equals(((Map<?, ?>) p).get("passed"))).count());
        validation.put("testProbesTotal", probes.size());
        validation.put("securityUnsafeRejected", outputs.getOrDefault(Node.SECURITY, Map.of()).get("unsafeRejected"));
        validation.put("securityUnsafeTotal", outputs.getOrDefault(Node.SECURITY, Map.of()).get("unsafeTotal"));
        Object sections = outputs.getOrDefault(Node.DOCS, Map.of()).get("sections");
        validation.put("docsSections", sections instanceof Map<?, ?> m ? List.copyOf(m.keySet()) : List.of());
        report.put("validation", validation);
        report.put("parallelGroup", parallelGroup(stages));

        Map<String, Object> readiness = outputs.getOrDefault(Node.RELEASE_READINESS, Map.of());
        report.put("readiness", pick(readiness, "aud01", "taskChecks", "residualRisks"));
        report.put("policies", latestPolicies(runId, run.getPlanVersion()));
        report.put("releaseApproval", pick(outputs.getOrDefault(Node.RELEASE_APPROVAL, Map.of()), "decisionId",
                "approvedBy", "acceptedRisks"));

        List<Map<String, Object>> lineage = new ArrayList<>();
        for (Decision d : decisions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("type", d.getType().name());
            row.put("gate", d.getGate());
            row.put("actor", d.getActorType() + "/" + d.getActorIdentity());
            row.put("planVersion", d.getPlanVersion());
            lineage.add(row);
        }
        report.put("decisions", lineage);
        List<Map<String, Object>> stageRows = new ArrayList<>();
        for (WorkflowStage s : stages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", s.getNode().name());
            row.put("status", s.getStatus().name());
            row.put("provenance", s.getProvenance() == null ? null : s.getProvenance().name());
            stageRows.add(row);
        }
        report.put("stages", stageRows);
        report.put("citations", citations(trail, decisions));
        return StageResult.success(normalize(report), Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        return output.get("citations") instanceof Map<?, ?> c && !c.isEmpty() ? Optional.empty()
                : Optional.of("report cites no audit events");
    }

    /** Persisted intervals of the parallel group, with the pairwise-overlap check of SC-002. */
    private static Map<String, Object> parallelGroup(List<WorkflowStage> stages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<WorkflowStage> group = stages.stream().filter(s -> PARALLEL.contains(s.getNode())).toList();
        for (WorkflowStage s : group) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", s.getNode().name());
            row.put("thread", s.getThreadName());
            row.put("startedAt", String.valueOf(s.getStartedAt()));
            row.put("endedAt", String.valueOf(s.getEndedAt()));
            rows.add(row);
        }
        boolean overlap = group.size() == PARALLEL.size() && group.stream().allMatch(a -> group.stream()
                .allMatch(b -> a.getStartedAt() != null && b.getEndedAt() != null && !a.getStartedAt().isAfter(b.getEndedAt())));
        Instant lastEnd = group.stream().map(WorkflowStage::getEndedAt).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        Instant joinStart = stages.stream().filter(s -> s.getNode() == Node.RELEASE_READINESS).map(WorkflowStage::getStartedAt)
                .findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intervals", rows);
        result.put("distinctThreads", group.stream().map(WorkflowStage::getThreadName).distinct().count());
        result.put("intervalsOverlapPairwise", overlap);
        result.put("joinStartedAfterAllEnded", lastEnd != null && joinStart != null && !joinStart.isBefore(lastEnd));
        return result;
    }

    private List<Map<String, Object>> latestPolicies(UUID runId, int planVersion) {
        Map<String, PolicyEvaluation> latest = new LinkedHashMap<>();
        policies.findByRunIdOrderByIdAsc(runId).stream().filter(p -> p.getPlanVersion() == planVersion)
                .forEach(p -> latest.put(p.getCheckId(), p));
        List<Map<String, Object>> rows = new ArrayList<>();
        latest.values().forEach(p -> rows.add(Map.of("checkId", p.getCheckId(), "result", p.getResult(),
                "node", p.getNode())));
        return rows;
    }

    /** Claim → audit sequence number that supports it (the latest such event). */
    private Map<String, Object> citations(List<AuditEvent> trail, List<Decision> decisions) {
        Map<String, Object> citations = new LinkedHashMap<>();
        for (AuditEvent e : trail) {
            if (e.getNode() != null && (e.getType() == AuditEventType.STAGE_SUCCEEDED
                    || e.getType() == AuditEventType.STAGE_SKIPPED)) {
                citations.put("stage." + e.getNode().name(), e.getSeq());
            } else if (e.getType() == AuditEventType.BRANCH_TAKEN && e.getNode() != null) {
                citations.put("branch." + e.getNode().name(), e.getSeq());
            } else if (e.getType() == AuditEventType.POLICY_EVALUATED) {
                field(e.getPayloadJson(), "checkId").ifPresent(id -> citations.put("policy." + id, e.getSeq()));
            } else if (e.getType() == AuditEventType.RUN_CREATED) {
                citations.put("run.created", e.getSeq());
            }
        }
        for (Decision d : decisions) {
            AuditEventType type = switch (d.getType()) {
                case APPROVAL -> AuditEventType.APPROVAL_GRANTED;
                case IMPLEMENTATION_EVIDENCE -> AuditEventType.IMPLEMENTATION_RECORDED;
                default -> null;
            };
            if (type == null) {
                continue;
            }
            trail.stream().filter(e -> e.getType() == type
                    && field(e.getPayloadJson(), "decisionId").map(String::valueOf).orElse("").equals(String.valueOf(d.getId())))
                    .reduce((a, b) -> b).ifPresent(e -> citations.put("decision." + d.getGate(), e.getSeq()));
        }
        return citations;
    }

    private Optional<String> field(String payload, String name) {
        try {
            JsonNode value = payload == null ? null : json.readTree(payload).get(name);
            return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> pick(Map<String, Object> source, String... keys) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String key : keys) {
            picked.put(key, source.get(key));
        }
        return picked;
    }

    /** Same JSON types as the persisted copy, so a regenerated report compares equal to the stored one. */
    private Map<String, Object> normalize(Map<String, Object> report) {
        try {
            return json.readValue(json.writeValueAsString(report), MAP);
        } catch (Exception e) {
            throw new IllegalStateException("report is not serializable", e);
        }
    }
}
