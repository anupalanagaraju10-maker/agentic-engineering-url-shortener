package com.agentic.shortener.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T121 (FR-SCN-003, SC-007): SCN-C "Make links expire." genuinely suspends at CLARIFICATION; nothing after the
 * gate runs before a HUMAN clarification; the clarification replans (plan 2) and plan-1 decisions are refused;
 * whether implementation is needed is decided by the replanned DESIGN against the current registry. Decisions
 * and evidence are labelled TEST FIXTURES.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioCTest {

    static final String SCN_C = "Make links expire.";
    static final String WITHIN_RECORD = "Expiration is optional per link: the client may supply an absolute expiration "
            + "time when creating a link; after that time the link returns the expired result; links without an "
            + "expiration never expire; existing links are unaffected.";
    static final String NEW_BEHAVIOR = "Links expire after 100 redirects; the client may supply that limit per link; "
            + "existing links are unaffected.";

    @Autowired
    private MockMvc mvc;

    @Test
    void aClarificationWithinRecordedBehaviorCompletesWithoutNewCode() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = suspended(api);

        api.clarify(runId, WITHIN_RECORD, 1, "HUMAN").andExpect(status().isOk());
        assertThat(api.planVersion(runId)).isEqualTo(2);
        assertThat(design(api, runId).get("implementationRequired")).isEqualTo(false);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isConflict())   // plan-1 decision refused
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));
        api.approve(runId, "DESIGN_APPROVAL", 2).andExpect(status().isOk());

        noChangeEvidence(api, runId).andExpect(status().isOk());
        assertThat(api.stageField(runId, "IMPLEMENT", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.stageField(runId, "TEST", "status")).isEqualTo("SUCCEEDED"); // the running build is validated
        api.approveRelease(runId, 2, List.of("fixture")).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void aClarificationThatNeedsNewBehaviorRequiresImplementation() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = suspended(api);

        api.clarify(runId, NEW_BEHAVIOR, 1, "HUMAN").andExpect(status().isOk());
        assertThat(api.planVersion(runId)).isEqualTo(2);
        assertThat(design(api, runId).get("implementationRequired")).isEqualTo(true);
        api.approve(runId, "DESIGN_APPROVAL", 2).andExpect(status().isOk());

        noChangeEvidence(api, runId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
        assertThat(api.decisionTypes(runId)).contains("DECISION_REFUSED");
    }

    /** "Make links expire." stops at CLARIFICATION with AMB-R2 and AMB-R4; nothing after the gate has run. */
    private UUID suspended(WorkflowApiClient api) throws Exception {
        UUID runId = api.createRun(SCN_C);
        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("AWAITING_CLARIFICATION");
        List<String> rules = JsonPath.read(run, "$.stages[?(@.node == 'UNDERSTAND')].output.findings[*].ruleId");
        assertThat(rules).contains("AMB-R2", "AMB-R4");
        for (String node : List.of("DECOMPOSE", "IMPACT_ANALYSIS", "DESIGN", "IMPLEMENT", "TEST")) {
            assertThat(api.stageField(runId, node, "status")).as(node).isEqualTo("PENDING");
            assertThat(api.stageField(runId, node, "attempts")).as(node).isEqualTo(0);
        }
        return runId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> design(WorkflowApiClient api, UUID runId) throws Exception {
        return (Map<String, Object>) api.stageField(runId, "DESIGN", "output");
    }

    private static ResultActions noChangeEvidence(WorkflowApiClient api, UUID runId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("actorType", "HUMAN");
        body.put("actorIdentity", "candidate");
        body.put("planVersion", api.planVersion(runId));
        body.put("summary", "TEST FIXTURE: the clarified behavior is already implemented; no code change");
        body.put("changedArtifacts", List.of());
        body.put("noChangeJustification", "TEST FIXTURE: optional absolute expiration already exists (FR-URL-008/009)");
        body.put("requirementIds", design(api, runId).get("requirementIds"));
        return api.evidence(runId, body);
    }
}
