package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T082 (FR-REL-006, ADR-0005 §5/§6, H3): a failed attempt commits nothing (ATTEMPT_ROLLED_BACK); probe links it
 * already committed are removed by compensation; a compensation failure is a non-recoverable safe-stop; a
 * timed-out TEST attempt creates no further probe link and leaves none behind.
 */
@SpringBootTest(properties = { "workflow.fault-injection.enabled=true", "workflow.stage-timeout=300ms" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RollbackCompensationTest {

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
    void aTestFaultAfterProbeCreationIsRolledBackCompensatedAndRetried() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("TEST", "TRANSIENT", 1)));
        api.throughValidation(runId);

        assertThat(types(runId, "TEST")).containsSubsequence("STAGE_STARTED", "STAGE_FAILED", "ATTEMPT_ROLLED_BACK",
                "COMPENSATION_STARTED", "COMPENSATION_COMPLETED", "RETRY_SCHEDULED", "STAGE_STARTED", "STAGE_SUCCEEDED");
        Map<String, Object> compensation = first(runId, "COMPENSATION_COMPLETED");
        assertThat(((Number) payload(compensation).get("deleted")).intValue())
                .as("the failed attempt's committed probe links were removed").isPositive();
        assertThat(api.stageField(runId, "TEST", "attempts")).isEqualTo(2);
        assertThat(links.countProbeLinks(runId)).isZero();
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aCompensationFailureSafeStopsNonRecoverably() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("TEST", "COMPENSATION_FAILURE", 1)));
        api.throughValidation(runId);

        String run = api.runJson(runId);
        assertThat(api.eventTypes(runId)).contains("COMPENSATION_FAILED");
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("SAFE_STOPPED");
        assertThat(JsonPath.<Boolean>read(run, "$.recoverable")).isFalse();
        assertThat(JsonPath.<String>read(run, "$.statusReason")).contains("compensation");
    }

    @Test
    void aPermanentNonDefectFailureIsCompensatedAndFailsTheRun() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("TEST", "PERMANENT", 1)));
        api.throughValidation(runId);

        assertThat(types(runId, "TEST")).containsSubsequence("STAGE_FAILED", "COMPENSATION_STARTED",
                "COMPENSATION_COMPLETED");
        assertThat(types(runId, "TEST")).doesNotContain("RETRY_SCHEDULED");
        assertThat(links.countProbeLinks(runId)).isZero();
        assertThat(api.status(runId)).isEqualTo("FAILED");
    }

    @Test
    void terminateRunsTheCompensationSweep() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.throughValidation(runId); // waits at RELEASE_APPROVAL
        links.createProbeLink("https://example.com/leftover", runId); // a leftover probe link of this run
        assertThat(links.countProbeLinks(runId)).isEqualTo(1);

        api.terminate(runId).andExpect(status().isOk());

        assertThat(links.countProbeLinks(runId)).isZero();
        assertThat(api.eventTypes(runId)).containsSubsequence("COMPENSATION_STARTED", "COMPENSATION_COMPLETED",
                "RUN_FAILED");
        assertThat(api.status(runId)).isEqualTo("FAILED");
    }

    @Test
    void aTimedOutTestAttemptCreatesNoFurtherProbeLinksAndLeavesNone() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                long until = System.currentTimeMillis() + 800; // slow, and deliberately ignores interruption
                while (System.currentTimeMillis() < until) {
                    Thread.onSpinWait();
                }
            }
            return invocation.callRealMethod();
        }).when(links).createProbeLink(anyString(), any(UUID.class), any());

        UUID runId = api.createRun(SCN_A);
        api.throughValidation(runId);

        assertThat(types(runId, "TEST")).containsSubsequence("STAGE_TIMED_OUT", "ATTEMPT_ROLLED_BACK",
                "COMPENSATION_STARTED", "COMPENSATION_COMPLETED", "STAGE_STARTED", "STAGE_SUCCEEDED");
        // attempt 1 made exactly one (slow) call and then stopped on its revoked token; attempt 2 made its 4
        assertThat(calls.get()).isEqualTo(1 + 4);
        assertThat(links.countProbeLinks(runId)).isZero();
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    private List<String> types(UUID runId, String node) throws Exception {
        return api.events(runId).stream().filter(e -> node.equals(e.get("node"))).map(e -> (String) e.get("type"))
                .toList();
    }

    private Map<String, Object> first(UUID runId, String type) throws Exception {
        return api.events(runId).stream().filter(e -> type.equals(e.get("type"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }
}
