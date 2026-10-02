package com.agentic.shortener.workflow.stages;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.CancellationToken;
import com.agentic.shortener.workflow.engine.DecisionType;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageStatus;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.DecisionRow;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.EventRow;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.Input;
import com.agentic.shortener.workflow.stages.ReleaseReadiness.PolicyRow;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T067: RELEASE_READINESS (FR-POL-006, AUD-01, H3) and FINAL_REPORT (FR-OBS-006, NFR-006).
 * The readiness rules are evaluated as a pure function; the report is checked on a real completed run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseReadinessAndReportTest {

    @Test
    void aCompleteRunIsReadyWithAud01PassAndResidualRisks() {
        Map<String, Object> result = ReleaseReadiness.evaluate(baseline().build());

        assertThat(result.get("ready")).isEqualTo(true);
        assertThat(strings(result.get("blockers"))).isEmpty();
        assertThat(map(result.get("aud01")).get("result")).isEqualTo("PASS");
        assertThat(strings(result.get("residualRisks")))
                .anyMatch(r -> r.contains("RedirectController") && r.contains("not cited"))
                .anyMatch(r -> r.contains("self-reported"));
    }

    @Test
    void anUnresolvedMandatoryPolicyFailureBlocksRelease() {
        Input input = baseline().policy(new PolicyRow("SEC-01", true, "FAIL", null, 1)).build();

        Map<String, Object> result = ReleaseReadiness.evaluate(input);

        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(strings(result.get("blockers"))).anyMatch(b -> b.contains("SEC-01"));
    }

    @Test
    void anExceptionBlocksUntilAHumanApprovedIt() {
        Input unapproved = baseline().policy(new PolicyRow("PRIV-01", true, "EXCEPTION_REQUESTED", null, 1)).build();
        assertThat(ReleaseReadiness.evaluate(unapproved).get("ready")).isEqualTo(false);
        assertThat(strings(ReleaseReadiness.evaluate(unapproved).get("blockers")))
                .anyMatch(b -> b.contains("PRIV-01") && b.contains("not approved"));

        Input approved = baseline().policy(new PolicyRow("PRIV-01", true, "EXCEPTION_REQUESTED", 90L, 1))
                .decision(new DecisionRow(90, DecisionType.EXCEPTION_APPROVED, "PRIV-01", 1),
                        AuditEventType.EXCEPTION_APPROVED, null)
                .build();
        assertThat(ReleaseReadiness.evaluate(approved).get("ready")).isEqualTo(true);

        Input rejected = baseline().policy(new PolicyRow("PRIV-01", true, "EXCEPTION_REQUESTED", 91L, 1))
                .decision(new DecisionRow(91, DecisionType.EXCEPTION_REJECTED, "PRIV-01", 1),
                        AuditEventType.EXCEPTION_REJECTED, null)
                .build();
        assertThat(ReleaseReadiness.evaluate(rejected).get("ready")).isEqualTo(false);
    }

    @Test
    void policyRowsOfAnEarlierPlanVersionAreIgnored() {
        Input input = baseline().policy(new PolicyRow("SEC-01", true, "FAIL", null, 0)).build();
        assertThat(ReleaseReadiness.evaluate(input).get("ready")).isEqualTo(true);
    }

    @Test
    void aud01FailsWhenAnExecutedNodeOrDecisionHasNoAuditEvent() {
        Builder missingStageEvent = baseline();
        missingStageEvent.events.removeIf(e -> e.type() == AuditEventType.STAGE_SUCCEEDED && e.node() == Node.DOCS);
        missingStageEvent.renumber();
        Map<String, Object> noStage = ReleaseReadiness.evaluate(missingStageEvent.build());
        assertThat(map(noStage.get("aud01")).get("result")).isEqualTo("FAIL");
        assertThat(strings(map(noStage.get("aud01")).get("missing"))).anyMatch(m -> m.contains("DOCS"));
        assertThat(noStage.get("ready")).isEqualTo(false);

        Builder missingApproval = baseline();
        missingApproval.events.removeIf(e -> e.type() == AuditEventType.APPROVAL_GRANTED);
        missingApproval.renumber();
        assertThat(strings(map(ReleaseReadiness.evaluate(missingApproval.build()).get("aud01")).get("missing")))
                .anyMatch(m -> m.contains("decision 3"));
    }

    @Test
    void aud01FailsOnAGapInTheAuditSequence() {
        Builder gap = baseline();
        EventRow last = gap.events.remove(gap.events.size() - 1);
        gap.events.add(new EventRow(last.seq() + 5, last.type(), last.node(), last.decisionId()));
        assertThat(map(ReleaseReadiness.evaluate(gap.build()).get("aud01")).get("reason").toString())
                .contains("sequence");
    }

    @Test
    void everyTaskNeedsAPassingCheck() {
        Builder failing = baseline();
        failing.outputs.put(Node.TEST, Map.of("probes", List.of(Map.of("id", "probe.create-link", "passed", false))));
        Map<String, Object> result = ReleaseReadiness.evaluate(failing.build());

        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(strings(result.get("blockers"))).anyMatch(b -> b.contains("TASK-1"));
    }

    @Test
    void remainingProbeLinksBlockRelease() {
        Builder leftover = baseline();
        leftover.probeLinksRemaining = 2;
        Map<String, Object> result = ReleaseReadiness.evaluate(leftover.build());

        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(strings(result.get("blockers"))).anyMatch(b -> b.contains("probe link"));
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FinalReportExecutor reportExecutor;
    @Autowired
    private WorkflowStore store;
    @Autowired
    private AuditEventRepository events;

    @Test
    void reportIsUnavailableUntilFinalReportSucceeded() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        api.fetch(runId, "/report").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
    }

    @Test
    void completedRunHasAnIdempotentReportCitingAuditSequenceNumbers() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        api.fixtureEvidence(runId).andExpect(status().isOk());
        api.approveRelease(runId, 1, List.of("fixture risk acceptance")).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("COMPLETED");

        Map<String, Object> stored = store.outputs(runId).get(Node.FINAL_REPORT);
        Map<String, Object> regenerated = reportExecutor.execute(new StageContext(runId, SCN_A, 1,
                store.outputs(runId), new CancellationToken())).output();
        assertThat(regenerated).isEqualTo(stored);

        Set<Integer> seqs = events.findByRunIdOrderBySeqAsc(runId).stream().map(AuditEvent::getSeq)
                .collect(Collectors.toSet());
        Map<String, Object> citations = map(stored.get("citations"));
        assertThat(citations).containsKeys("stage.DESIGN", "stage.TEST", "decision.DESIGN_APPROVAL",
                "decision.IMPLEMENT", "decision.RELEASE_APPROVAL");
        citations.values().forEach(seq -> assertThat(seqs).contains(((Number) seq).intValue()));
        assertThat(map(stored.get("releaseApproval")).get("acceptedRisks"))
                .isEqualTo(List.of("fixture risk acceptance"));
        assertThat(strings(map(stored.get("requirement")).get("requirementIds"))).contains("FR-URL-001");

        api.fetch(runId, "/report").andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.outcome").value("RELEASE_APPROVED"));
    }

    // ------------------------------------------------------------------ fixtures

    /** A consistent SCN-A-like state just before RELEASE_READINESS (test fixture, not live evidence). */
    static Builder baseline() {
        Builder b = new Builder();
        for (Node n : List.of(Node.INTAKE, Node.UNDERSTAND, Node.DECOMPOSE, Node.DESIGN, Node.DESIGN_APPROVAL,
                Node.IMPLEMENT, Node.TEST, Node.DOCS, Node.SECURITY)) {
            b.stages.put(n, StageStatus.SUCCEEDED);
        }
        b.stages.put(Node.CLARIFICATION, StageStatus.SKIPPED);
        b.stages.put(Node.IMPACT_ANALYSIS, StageStatus.SKIPPED);
        b.stages.put(Node.RELEASE_READINESS, StageStatus.RUNNING);
        b.stages.put(Node.RELEASE_APPROVAL, StageStatus.PENDING);
        b.stages.put(Node.FINAL_REPORT, StageStatus.PENDING);

        b.outputs.put(Node.UNDERSTAND, Map.of("capabilities", List.of("CREATE_LINK")));
        b.outputs.put(Node.DECOMPOSE, Map.of("tasks", List.of(Map.of("id", "TASK-1", "capability", "CREATE_LINK",
                "acceptanceChecks", List.of("probe.create-link")))));
        b.outputs.put(Node.DESIGN, Map.of("components", List.of("LinkService", "RedirectController")));
        b.outputs.put(Node.IMPLEMENT, Map.of("uncoveredComponents", List.of("RedirectController")));
        b.outputs.put(Node.TEST, Map.of("probes", List.of(Map.of("id", "probe.create-link", "passed", true))));

        b.policies.add(new PolicyRow("PRIV-01", true, "PASS", null, 1));
        b.policies.add(new PolicyRow("SEC-01", true, "PASS", null, 1));
        b.policies.add(new PolicyRow("CHG-01", true, "NOT_APPLICABLE", null, 1));
        b.policies.add(new PolicyRow("DEP-01", true, "NOT_APPLICABLE", null, 1));

        b.event(AuditEventType.RUN_CREATED, null, null);
        for (Node n : List.of(Node.INTAKE, Node.UNDERSTAND)) {
            b.event(AuditEventType.STAGE_STARTED, n, null);
            b.event(AuditEventType.STAGE_SUCCEEDED, n, null);
        }
        b.decision(new DecisionRow(1, DecisionType.BRANCH, "CLARIFICATION", 1), AuditEventType.BRANCH_TAKEN,
                Node.CLARIFICATION);
        b.event(AuditEventType.STAGE_SKIPPED, Node.CLARIFICATION, null);
        b.event(AuditEventType.STAGE_SUCCEEDED, Node.DECOMPOSE, null);
        b.decision(new DecisionRow(2, DecisionType.BRANCH, "IMPACT_ANALYSIS", 1), AuditEventType.BRANCH_TAKEN,
                Node.IMPACT_ANALYSIS);
        b.event(AuditEventType.STAGE_SKIPPED, Node.IMPACT_ANALYSIS, null);
        b.event(AuditEventType.STAGE_SUCCEEDED, Node.DESIGN, null);
        b.decision(new DecisionRow(3, DecisionType.APPROVAL, "DESIGN_APPROVAL", 1), AuditEventType.APPROVAL_GRANTED,
                Node.DESIGN_APPROVAL);
        b.event(AuditEventType.STAGE_SUCCEEDED, Node.DESIGN_APPROVAL, null);
        b.decision(new DecisionRow(4, DecisionType.IMPLEMENTATION_EVIDENCE, "IMPLEMENT", 1),
                AuditEventType.IMPLEMENTATION_RECORDED, Node.IMPLEMENT);
        b.event(AuditEventType.STAGE_SUCCEEDED, Node.IMPLEMENT, null);
        for (Node n : List.of(Node.TEST, Node.DOCS, Node.SECURITY)) {
            b.event(AuditEventType.STAGE_SUCCEEDED, n, null);
        }
        b.event(AuditEventType.STAGE_STARTED, Node.RELEASE_READINESS, null);
        return b;
    }

    static final class Builder {
        final Map<Node, StageStatus> stages = new EnumMap<>(Node.class);
        final Map<Node, Map<String, Object>> outputs = new EnumMap<>(Node.class);
        final List<PolicyRow> policies = new ArrayList<>();
        final List<DecisionRow> decisions = new ArrayList<>();
        final List<EventRow> events = new ArrayList<>();
        long probeLinksRemaining;

        Builder policy(PolicyRow row) {
            policies.add(row);
            return this;
        }

        Builder decision(DecisionRow row, AuditEventType eventType, Node node) {
            decisions.add(row);
            event(eventType, node, row.id());
            return this;
        }

        void event(AuditEventType type, Node node, Long decisionId) {
            events.add(new EventRow(events.size() + 1, type, node, decisionId));
        }

        void renumber() {
            List<EventRow> copy = new ArrayList<>(events);
            events.clear();
            copy.forEach(e -> event(e.type(), e.node(), e.decisionId()));
        }

        Input build() {
            return new Input(1, stages, outputs, policies, decisions, events, probeLinksRemaining);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
