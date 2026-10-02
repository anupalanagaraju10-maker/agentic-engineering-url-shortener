package com.agentic.shortener.workflow.policy;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T100 (FR-POL-004/005/006, CHK006): an EXCEPTION_REQUESTED result blocks the run until a HUMAN decides.
 * Approval needs scope, compensating control and an expiry or review condition, all recorded; rejection is
 * a safe-stop; a dated expiry that has passed by RELEASE_READINESS counts as unapproved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyExceptionTest {

    static final String WITH_PII = SCN_A + " Also record each visitor's IP address.";

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void anExceptionRequestBlocksUntilAHumanApprovesWithEveryRequiredField() throws Exception {
        UUID runId = api.createRun(WITH_PII);
        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("AWAITING_APPROVAL");
        assertThat(JsonPath.<String>read(run, "$.pendingAction")).isEqualTo("EXCEPTION:PRIV-01");
        assertThat(api.stageField(runId, "DECOMPOSE", "status")).isEqualTo("PENDING");

        Map<String, Object> agent = approval("2099-12-31");
        agent.put("actorType", "AGENT");
        agent.put("actorIdentity", "claude-code");
        api.policyException(runId, "PRIV-01", agent).andExpect(status().isBadRequest());
        Map<String, Object> incomplete = approval("2099-12-31");
        incomplete.remove("compensatingControl");
        api.policyException(runId, "PRIV-01", incomplete).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        api.policyException(runId, "SEC-01", approval("2099-12-31")).andExpect(status().isConflict());
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");

        api.policyException(runId, "PRIV-01", approval("2099-12-31")).andExpect(status().isOk());

        Map<String, Object> decision = JsonPath.<List<Map<String, Object>>>read(
                api.fetch(runId, "/decisions").andReturn().getResponse().getContentAsString(),
                "$[?(@.type == 'EXCEPTION_APPROVED')]").get(0);
        assertThat(decision.get("gate")).isEqualTo("PRIV-01");
        assertThat(decision.get("actorType")).isEqualTo("HUMAN");
        assertThat(decision.get("reason")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) decision.get("payload");
        assertThat(record).containsEntry("policyId", "PRIV-01").containsEntry("scope", "visitor IP of this run only")
                .containsEntry("compensatingControl", "IP truncated to /24 and kept 7 days")
                .containsEntry("expiresOrReview", "2099-12-31").containsKey("approvedAt");
        assertThat(api.eventTypes(runId)).contains("EXCEPTION_REQUESTED", "EXCEPTION_APPROVED");
        List<Object> resolution = JsonPath.read(api.runJson(runId),
                "$.policyEvaluations[?(@.checkId == 'PRIV-01')].resolutionDecisionId");
        assertThat(resolution.get(0)).isEqualTo(decision.get("id"));
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(api.stageField(runId, "DESIGN", "status")).isEqualTo("SUCCEEDED"); // the run moved on
        assertThat(JsonPath.<String>read(api.runJson(runId), "$.pendingAction")).isEqualTo("APPROVE:DESIGN_APPROVAL");
    }

    @Test
    void aRejectedExceptionSafeStops() throws Exception {
        UUID runId = api.createRun(WITH_PII);
        Map<String, Object> reject = new HashMap<>(Map.of("actorType", "HUMAN", "actorIdentity", "candidate",
                "reason", "personal data is out of scope", "decision", "REJECT", "planVersion", 1));

        api.policyException(runId, "PRIV-01", reject).andExpect(status().isOk());

        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("SAFE_STOPPED");
        assertThat(JsonPath.<Boolean>read(run, "$.recoverable")).isFalse();
        assertThat(api.decisionTypes(runId)).contains("EXCEPTION_REJECTED");
    }

    @Test
    void anExpiredExceptionIsUnapprovedAtReleaseReadiness() throws Exception {
        UUID runId = api.createRun(WITH_PII);
        api.policyException(runId, "PRIV-01", approval("2020-01-01")).andExpect(status().isOk());
        api.throughValidation(runId);

        String run = api.runJson(runId);
        assertThat(api.stageField(runId, "RELEASE_READINESS", "status")).isEqualTo("FAILED");
        assertThat(String.valueOf(api.stageField(runId, "RELEASE_READINESS", "failureReason")))
                .contains("PRIV-01").contains("expired");
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("SAFE_STOPPED");
        assertThat(api.stageField(runId, "RELEASE_APPROVAL", "status")).isEqualTo("PENDING");
    }

    private static Map<String, Object> approval(String expiresOrReview) {
        Map<String, Object> body = new HashMap<>();
        body.put("actorType", "HUMAN");
        body.put("actorIdentity", "candidate");
        body.put("reason", "analytics need coarse visitor location");
        body.put("decision", "APPROVE");
        body.put("planVersion", 1);
        body.put("scope", "visitor IP of this run only");
        body.put("compensatingControl", "IP truncated to /24 and kept 7 days");
        body.put("expiresOrReview", expiresOrReview);
        return body;
    }
}
