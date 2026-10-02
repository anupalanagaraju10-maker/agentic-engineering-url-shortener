package com.agentic.shortener.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T098 (FR-SCN-003, CHK010, CHK034): a HUMAN clarification replans from UNDERSTAND (plan + 1) with the
 * CLARIFICATION gate kept SUCCEEDED; a still-ambiguous answer opens another round; a clarification that
 * contradicts an approved requirement is refused (409 CHANGE_CONTROL_REQUIRED) and must go through
 * requirement-change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClarificationTest {

    static final String SCN_C = "Make links expire.";
    static final String ANSWER = "Expiration is optional per link: the client may supply an absolute expiration time "
            + "when creating a link; after that time the link returns the expired result; links without an expiration "
            + "never expire; existing links are unaffected.";

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void aClarificationReplansFromUnderstandAndTheRunContinues() throws Exception {
        UUID runId = api.createRun(SCN_C);
        assertThat(api.status(runId)).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(api.stageField(runId, "DECOMPOSE", "status")).isEqualTo("PENDING");

        api.clarify(runId, ANSWER, 1, "HUMAN").andExpect(status().isOk());

        assertThat(api.planVersion(runId)).isEqualTo(2);
        assertThat(api.stageField(runId, "CLARIFICATION", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.eventTypes(runId)).containsSubsequence("CLARIFICATION_RECEIVED", "PLAN_REPLANNED",
                "STAGE_STARTED");
        assertThat(api.decisionTypes(runId)).contains("CLARIFICATION");
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) ((java.util.Map<String, Object>) api.stageField(runId, "UNDERSTAND",
                "output")).get("findings");
        assertThat(findings).isEmpty();
        assertThat(api.stageField(runId, "IMPACT_ANALYSIS", "status")).isEqualTo("SUCCEEDED"); // "existing" ⇒ brownfield
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isConflict());           // stale plan
        api.approve(runId, "DESIGN_APPROVAL", 2).andExpect(status().isOk());
    }

    @Test
    void aStillAmbiguousAnswerOpensAnotherRound() throws Exception {
        UUID runId = api.createRun(SCN_C);

        api.clarify(runId, "Links should expire soon.", 1, "HUMAN").andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(api.planVersion(runId)).isEqualTo(2);

        api.clarify(runId, ANSWER, 2, "HUMAN").andExpect(status().isOk());
        assertThat(api.planVersion(runId)).isEqualTo(3);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(api.decisionTypes(runId)).filteredOn("CLARIFICATION"::equals).hasSize(2);
    }

    @Test
    void aClarificationContradictingAnApprovedRequirementNeedsChangeControl() throws Exception {
        UUID runId = api.createRun(SCN_C);

        api.clarify(runId, "All links expire after 30 days.", 1, "HUMAN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("CHANGE_CONTROL_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("FR-URL-008")));

        assertThat(api.decisionTypes(runId)).contains("DECISION_REFUSED").doesNotContain("CLARIFICATION");
        assertThat(api.status(runId)).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(api.planVersion(runId)).isEqualTo(1);
    }

    @Test
    void staleAgentAndMisdirectedClarificationsAreRefused() throws Exception {
        UUID runId = api.createRun(SCN_C);
        api.clarify(runId, ANSWER, 7, "HUMAN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));
        api.clarify(runId, ANSWER, 1, "AGENT").andExpect(status().isBadRequest());

        UUID notWaiting = api.createRun(WorkflowApiClient.SCN_A);
        api.clarify(notWaiting, ANSWER, 1, "HUMAN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
    }
}
