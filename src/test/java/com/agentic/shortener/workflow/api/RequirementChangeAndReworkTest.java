package com.agentic.shortener.workflow.api;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.link.LinkService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T099 (FR-HUM-005, FR-ORC-013, CHK036, NFR-008): requirement change and rework are HUMAN-only replans.
 * Rework re-runs only the chosen node and its descendants; it must start at or upstream of the rejected or
 * failed node.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequirementChangeAndReworkTest {

    @Autowired
    private MockMvc mvc;
    @MockitoSpyBean
    private LinkService links;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void requirementChangeAfterDesignApprovalRequiresANewApprovalAndListsChangedRequirements() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1);
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");

        api.requirementChange(runId, SCN_A + " Deduplicate links so the same URL returns the same link.", 1)
                .andExpect(status().isOk());

        assertThat(api.planVersion(runId)).isEqualTo(2);
        assertThat(api.decisionTypes(runId)).contains("REQUIREMENT_CHANGE", "DECISION_INVALIDATED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        @SuppressWarnings("unchecked")
        Map<String, Object> design = (Map<String, Object>) api.stageField(runId, "DESIGN", "output");
        assertThat(design.get("changesApprovedRequirements")).isEqualTo(List.of("FR-URL-011"));
        api.fixtureEvidence(runId).andExpect(status().isConflict()); // evidence before the new approval is refused
    }

    @Test
    void reworkFromDocsAfterAReleaseRejectionPreservesImplementationAndValidation() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.throughValidation(runId);
        Object evidenceDecision = ((Map<?, ?>) api.stageField(runId, "IMPLEMENT", "output")).get("decisionId");
        api.gate(runId, "reject", "RELEASE_APPROVAL", "HUMAN", "candidate", 1).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_REWORK");

        api.rework(runId, "DOCS", 1, "HUMAN").andExpect(status().isOk());

        assertThat(api.planVersion(runId)).isEqualTo(2);
        for (String preserved : List.of("IMPLEMENT", "TEST", "SECURITY")) {
            assertThat(api.stageField(runId, preserved, "status")).isEqualTo("SUCCEEDED");
            assertThat(api.stageField(runId, preserved, "planVersion")).isEqualTo(1);
        }
        assertThat(((Map<?, ?>) api.stageField(runId, "IMPLEMENT", "output")).get("decisionId")).isEqualTo(evidenceDecision);
        assertThat(api.stageField(runId, "TEST", "attempts")).isEqualTo(1);
        for (String rerun : List.of("DOCS", "RELEASE_READINESS")) {
            assertThat(api.stageField(runId, rerun, "status")).isEqualTo("SUCCEEDED");
            assertThat(api.stageField(runId, rerun, "planVersion")).isEqualTo(2);
        }
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        api.approveRelease(runId, 2, List.of("fixture")).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void reworkFromImplementAfterADefectNeedsNewEvidenceAndRevalidation() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1);
        doReturn("https://wrong.example.com/").when(links).redirect(anyString()); // defective build (fixture)
        api.fixtureEvidence(runId);
        assertThat(api.status(runId)).isEqualTo("AWAITING_REWORK");
        doCallRealMethod().when(links).redirect(anyString());                    // the defect is fixed

        api.rework(runId, "IMPLEMENT", 1, "HUMAN").andExpect(status().isOk());

        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
        assertThat(api.planVersion(runId)).isEqualTo(2);
        assertThat(api.decisionTypes(runId)).contains("REWORK", "DECISION_INVALIDATED");
        assertThat(api.stageField(runId, "DESIGN_APPROVAL", "status")).isEqualTo("SUCCEEDED"); // upstream kept
        api.fixtureEvidence(runId).andExpect(status().isOk());                       // new evidence at plan 2
        assertThat(api.stageField(runId, "TEST", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void reworkMustStartAtOrUpstreamOfTheRejectedNodeAndIsHumanOnly() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.throughValidation(runId);
        api.gate(runId, "reject", "RELEASE_APPROVAL", "HUMAN", "candidate", 1);

        api.rework(runId, "FINAL_REPORT", 1, "HUMAN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        api.rework(runId, "DOCS", 1, "AGENT").andExpect(status().isBadRequest());
        api.rework(runId, "DOCS", 5, "HUMAN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));
        assertThat(api.status(runId)).isEqualTo("AWAITING_REWORK");

        UUID notWaiting = api.createRun(SCN_A);
        api.rework(notWaiting, "DESIGN", 1, "HUMAN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
    }
}
