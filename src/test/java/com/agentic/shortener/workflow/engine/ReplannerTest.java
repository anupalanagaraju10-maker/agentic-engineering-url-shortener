package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.agentic.shortener.workflow.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T097 (FR-ORC-013, FR-POL-007, CHK007, SC-007): one replan mechanism. Affected = from-node plus descendants,
 * everything else preserved; prior outputs kept in STAGE_INVALIDATED; approvals and implementation evidence
 * invalidated; plan + 1 with PLAN_REPLANNED. A failure inside the replan transaction changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplannerTest {

    private static final AtomicBoolean FAIL_PLAN_REPLANNED = new AtomicBoolean();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private LinkService links;
    @MockitoSpyBean
    private AuditService audit;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
        doAnswer(invocation -> {
            if (FAIL_PLAN_REPLANNED.get() && invocation.getArgument(1) == AuditEventType.PLAN_REPLANNED) {
                throw new IllegalStateException("injected failure inside the replan transaction (test)");
            }
            return invocation.callRealMethod();
        }).when(audit).append(any(), any(), any(), any(), anyMap(), anyBoolean());
    }

    @AfterEach
    void disarm() {
        FAIL_PLAN_REPLANNED.set(false);
    }

    @Test
    void requirementChangeAfterEvidenceInvalidatesDownstreamAndKeepsPriorOutputs() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1);
        api.fixtureEvidence(runId); // now waiting at RELEASE_APPROVAL, plan 1

        api.requirementChange(runId, SCN_A + " Support an idempotency key on create requests.", 1)
                .andExpect(status().isOk());

        assertThat(api.planVersion(runId)).isEqualTo(2);
        Map<String, Object> replanned = single(runId, "PLAN_REPLANNED");
        assertThat(payload(replanned)).containsEntry("oldPlanVersion", 1).containsEntry("newPlanVersion", 2)
                .containsKey("reason");
        assertThat(strings(payload(replanned).get("affected"))).contains("UNDERSTAND", "DESIGN", "DESIGN_APPROVAL",
                "IMPLEMENT", "TEST", "DOCS", "SECURITY", "RELEASE_APPROVAL", "FINAL_REPORT").doesNotContain("INTAKE");
        assertThat(strings(payload(replanned).get("preserved"))).containsExactly("INTAKE");

        Map<String, Object> designInvalidated = api.events(runId).stream()
                .filter(e -> "STAGE_INVALIDATED".equals(e.get("type")) && "DESIGN".equals(e.get("node"))).findFirst()
                .orElseThrow();
        assertThat(String.valueOf(payload(designInvalidated).get("priorOutput"))).contains("LinkService");

        List<String> decisions = api.decisionTypes(runId);
        assertThat(decisions).filteredOn("DECISION_INVALIDATED"::equals).hasSize(2); // design approval + evidence
        assertThat(api.stageField(runId, "INTAKE", "attempts")).isEqualTo(1);           // preserved, not re-run
        assertThat(api.stageField(runId, "INTAKE", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");                    // new design approval needed
        assertThat(api.stageField(runId, "DESIGN", "planVersion")).isEqualTo(2);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));
        api.approve(runId, "DESIGN_APPROVAL", 2).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");               // old evidence does not count
    }

    @Test
    void aFailureInsideTheReplanTransactionChangesNothing() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1);
        links.createProbeLink("https://example.com/probe-before-replan", runId); // would be swept by the replan
        String before = api.runJson(runId);
        List<String> decisionsBefore = api.decisionTypes(runId);

        FAIL_PLAN_REPLANNED.set(true);
        api.requirementChange(runId, SCN_A + " Support an idempotency key on create requests.", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("REPLAN_FAILED"));
        FAIL_PLAN_REPLANNED.set(false);

        assertThat(api.runJson(runId)).isEqualTo(before);                 // run and stages unchanged
        assertThat(api.decisionTypes(runId)).isEqualTo(decisionsBefore);   // no invalidation committed
        assertThat(links.countProbeLinks(runId)).isEqualTo(1);             // the sweep was rolled back too
        assertThat(api.eventTypes(runId)).contains("REPLAN_ABORTED").doesNotContain("STAGE_INVALIDATED",
                "PLAN_REPLANNED");
        links.deleteProbeLinks(runId);
    }

    private Map<String, Object> single(UUID runId, String type) throws Exception {
        List<Map<String, Object>> matching = api.events(runId).stream().filter(e -> type.equals(e.get("type"))).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
