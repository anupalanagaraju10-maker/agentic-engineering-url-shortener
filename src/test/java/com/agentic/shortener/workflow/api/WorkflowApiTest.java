package com.agentic.shortener.workflow.api;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T037: workflow API shape per contracts/openapi.yaml (FR-ORC-001, FR-ORC-015, NFR-005). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowApiTest {

    @Autowired
    private MockMvc mvc;

    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void createReturns201RunAndHonoursCorrelationId() throws Exception {
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).header("X-Correlation-Id", "corr-42")
                        .content("{\"requirement\":\"" + SCN_A + "\",\"actorType\":\"HUMAN\",\"actorIdentity\":\"candidate\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.correlationId").value("corr-42"))
                .andExpect(jsonPath("$.originalRequirement").value(SCN_A))
                .andExpect(jsonPath("$.changeType").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.policyVersion").value("v1"));
    }

    @Test
    void agentMaySubmitButSystemMayNot() throws Exception {
        api.create(SCN_A, "AGENT", "claude-code").andExpect(status().isCreated());
        api.create(SCN_A, "SYSTEM", "workflow-engine").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        api.create(" ", "HUMAN", "candidate").andExpect(status().isBadRequest());
        api.create("x".repeat(4001), "HUMAN", "candidate").andExpect(status().isBadRequest());
    }

    @Test
    void runViewExposesTheGraphAndPolicyResults() throws Exception {
        UUID runId = api.createRun(SCN_A);
        String run = api.runJson(runId);

        assertThat((List<?>) JsonPath.read(run, "$.stages")).hasSize(14);
        assertThat((List<String>) JsonPath.read(run, "$.stages[*].node")).containsExactly("INTAKE", "UNDERSTAND",
                "CLARIFICATION", "DECOMPOSE", "IMPACT_ANALYSIS", "DESIGN", "DESIGN_APPROVAL", "IMPLEMENT", "TEST",
                "DOCS", "SECURITY", "RELEASE_READINESS", "RELEASE_APPROVAL", "FINAL_REPORT");
        assertThat((List<String>) JsonPath.read(run, "$.stages[?(@.node == 'DESIGN_APPROVAL')].kind"))
                .containsExactly("HUMAN_GATE");
        assertThat((List<String>) JsonPath.read(run, "$.stages[?(@.node == 'IMPLEMENT')].kind"))
                .containsExactly("EXTERNAL_ACTION");
        assertThat((List<Boolean>) JsonPath.read(run, "$.stages[?(@.node == 'CLARIFICATION')].conditional"))
                .containsExactly(true);
        List<List<String>> joinDeps = JsonPath.read(run, "$.stages[?(@.node == 'RELEASE_READINESS')].dependsOn");
        assertThat(joinDeps.get(0)).containsExactlyInAnyOrder("TEST", "DOCS", "SECURITY");
        assertThat((String) JsonPath.read(run, "$.pendingAction")).isEqualTo("APPROVE:DESIGN_APPROVAL");
        assertThat((List<String>) JsonPath.read(run, "$.policyEvaluations[*].checkId"))
                .containsExactly("PRIV-01", "SEC-01", "CHG-01", "DEP-01");
    }

    @Test
    void eventsAndDecisionsAreOrdered() throws Exception {
        UUID runId = api.createRun(SCN_A);

        String events = api.fetch(runId, "/events").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> seqs = JsonPath.read(events, "$[*].seq");
        assertThat(seqs).isSorted().first().isEqualTo(1);
        assertThat((String) JsonPath.read(events, "$[0].type")).isEqualTo("RUN_CREATED");
        Map<String, Object> first = ((List<Map<String, Object>>) JsonPath.read(events, "$")).get(0);
        assertThat(first).containsKeys("seq", "type", "actorType", "actorIdentity", "runId", "correlationId",
                "planVersion", "policyVersion", "injected", "payload", "createdAt");

        String decisions = api.fetch(runId, "/decisions").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        List<Integer> ids = JsonPath.read(decisions, "$[*].id");
        assertThat(ids).isSorted().isNotEmpty();
    }

    @Test
    void unknownRunIs404Problem() throws Exception {
        api.fetch(UUID.randomUUID(), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }
}
