package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
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
 * T080 (FR-REL-001..004, PVT-001/002, SC-004, NFR-002): transient-only bounded retry (3 attempts, 100 ms then
 * 200 ms backoff), timeouts as transient failures, exhaustion ⇒ recoverable safe-stop; gates and IMPLEMENT
 * have no timeout. The stage timeout is shortened to 300 ms for this test.
 */
@SpringBootTest(properties = { "workflow.fault-injection.enabled=true", "workflow.stage-timeout=300ms" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetryTimeoutTest {

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void oneTransientFailureIsRetriedOnceAndSucceeds() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("UNDERSTAND", "TRANSIENT", 1)));

        assertThat(api.stageField(runId, "UNDERSTAND", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.stageField(runId, "UNDERSTAND", "attempts")).isEqualTo(2);
        assertThat(types(runId, "UNDERSTAND")).containsSubsequence("STAGE_STARTED", "STAGE_FAILED",
                "ATTEMPT_ROLLED_BACK", "RETRY_SCHEDULED", "STAGE_STARTED", "STAGE_SUCCEEDED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aPermanentFailureIsNeverRetried() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("UNDERSTAND", "PERMANENT", 1)));

        assertThat(api.stageField(runId, "UNDERSTAND", "status")).isEqualTo("FAILED");
        assertThat(api.stageField(runId, "UNDERSTAND", "attempts")).isEqualTo(1);
        assertThat(types(runId, "UNDERSTAND")).doesNotContain("RETRY_SCHEDULED");
        assertThat(api.status(runId)).isEqualTo("FAILED");
    }

    @Test
    void threeTransientFailuresExhaustRetriesAndSafeStopRecoverably() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("UNDERSTAND", "TRANSIENT", 3)));

        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("SAFE_STOPPED");
        assertThat(JsonPath.<Boolean>read(run, "$.recoverable")).isTrue();
        assertThat(JsonPath.<String>read(run, "$.pendingAction")).isEqualTo("RESUME");
        assertThat(JsonPath.<String>read(run, "$.statusReason")).contains("UNDERSTAND");
        assertThat(api.stageField(runId, "UNDERSTAND", "attempts")).isEqualTo(3);
        assertThat(api.stageField(runId, "UNDERSTAND", "status")).isEqualTo("PENDING"); // last attempt rolled back
        assertThat(types(runId, "UNDERSTAND")).filteredOn("RETRY_SCHEDULED"::equals).hasSize(2);
        assertThat(types(runId, "UNDERSTAND")).contains("RETRY_EXHAUSTED");
        assertThat(api.stageField(runId, "DECOMPOSE", "status")).isEqualTo("PENDING");
    }

    @Test
    void backoffIs100MsThen200Ms() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("INTAKE", "TRANSIENT", 2)));

        List<Map<String, Object>> retries = api.events(runId).stream()
                .filter(e -> "RETRY_SCHEDULED".equals(e.get("type"))).toList();
        assertThat(retries).extracting(e -> ((Number) payload(e).get("backoffMs")).intValue()).containsExactly(100, 200);
        assertThat(retries).extracting(e -> ((Number) payload(e).get("nextAttempt")).intValue()).containsExactly(2, 3);

        List<Map<String, Object>> events = api.events(runId);
        for (Map<String, Object> retry : retries) {
            int seq = ((Number) retry.get("seq")).intValue();
            Map<String, Object> nextStart = events.stream().filter(e -> ((Number) e.get("seq")).intValue() > seq)
                    .filter(e -> "STAGE_STARTED".equals(e.get("type"))).findFirst().orElseThrow();
            long gap = Duration.between(Instant.parse((String) retry.get("createdAt")),
                    Instant.parse((String) nextStart.get("createdAt"))).toMillis();
            assertThat(gap).isGreaterThanOrEqualTo(((Number) payload(retry).get("backoffMs")).longValue() - 5);
        }
        assertThat(api.stageField(runId, "INTAKE", "attempts")).isEqualTo(3);
        assertThat(api.stageField(runId, "INTAKE", "status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void aTimeoutIsRecordedAndRetriedAsTransient() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("UNDERSTAND", "TIMEOUT", 1)));

        assertThat(types(runId, "UNDERSTAND")).containsSubsequence("STAGE_STARTED", "STAGE_TIMED_OUT",
                "ATTEMPT_ROLLED_BACK", "RETRY_SCHEDULED", "STAGE_STARTED", "STAGE_SUCCEEDED");
        Map<String, Object> timedOut = api.events(runId).stream().filter(e -> "STAGE_TIMED_OUT".equals(e.get("type")))
                .findFirst().orElseThrow();
        assertThat(payload(timedOut).get("failureClass")).isEqualTo("TRANSIENT");
        assertThat(timedOut.get("injected")).isEqualTo(true);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void gatesAndTheExternalActionNeverTimeOut() throws Exception {
        UUID runId = api.createRun(SCN_A);
        Thread.sleep(700); // well past the 300 ms stage timeout while waiting at DESIGN_APPROVAL
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        Thread.sleep(700); // and while waiting for implementation evidence
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
        assertThat(api.eventTypes(runId)).doesNotContain("STAGE_TIMED_OUT", "STAGE_FAILED");
        api.fixtureEvidence(runId).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    private List<String> types(UUID runId, String node) throws Exception {
        return api.events(runId).stream().filter(e -> node.equals(e.get("node"))).map(e -> (String) e.get("type"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }
}
