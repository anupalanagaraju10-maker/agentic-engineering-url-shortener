package com.agentic.shortener.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 (FR-SCN-002): SCN-B brownfield end to end through the API. The brownfield branch is taken, the impact
 * report is complete before design approval, IMPLEMENT is not eligible before approval, and validation runs
 * the real expiration acceptance probe. Decisions and evidence are labelled TEST FIXTURES.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioBTest {

    static final String SCN_B = "Add optional expiration to existing links; expired links return an expired result "
            + "distinct from not-found.";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private LinkService links;

    @Test
    void brownfieldRunAnalysesImpactBeforeApprovalAndValidatesExpiration() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_B);

        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.changeType")).isEqualTo("BROWNFIELD");
        assertThat(api.stageField(runId, "CLARIFICATION", "status")).isEqualTo("SKIPPED");
        assertThat(api.stageField(runId, "IMPACT_ANALYSIS", "status")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) api.stageField(runId, "IMPACT_ANALYSIS", "output");
        assertThat(impact).containsKeys("currentBehavior", "requestedBehavior", "affectedComponents", "interfaces",
                "data", "tests", "documentation", "regressionRisks", "securityReliabilityImpact",
                "rollbackCompensation", "dataFlows");
        List<String> chg = JsonPath.read(run, "$.policyEvaluations[?(@.checkId == 'CHG-01')].result");
        assertThat(chg).containsExactly("PASS");

        // IMPLEMENT is not eligible before the HUMAN design approval
        assertThat(api.stageField(runId, "IMPLEMENT", "status")).isEqualTo("PENDING");
        api.fixtureEvidence(runId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));

        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
        api.fixtureEvidence(runId).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> probes = (List<Map<String, Object>>) ((Map<String, Object>) api.stageField(runId,
                "TEST", "output")).get("probes");
        assertThat(probes).anySatisfy(p -> {
            assertThat(p.get("id")).isEqualTo("probe.expired-link");
            assertThat(p.get("passed")).isEqualTo(true);
        });
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");

        api.approveRelease(runId, 1, List.of("fixture")).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("COMPLETED");
        assertThat(links.countProbeLinks(runId)).isZero();
    }
}
