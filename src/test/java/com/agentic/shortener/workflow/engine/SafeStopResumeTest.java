package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
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
 * T083 (FR-REL-007..010, SC-006): a recoverable safe-stop preserves state, reason, history and the flag;
 * resume is HUMAN-only and refused unless the run is a recoverable SAFE_STOPPED one; resume re-runs only the
 * failed branch of the parallel group and never re-executes succeeded nodes.
 */
@SpringBootTest(properties = "workflow.fault-injection.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SafeStopResumeTest {

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void resumeReRunsOnlyTheFailedParallelBranch() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("SECURITY", "TRANSIENT", 3)));
        api.throughValidation(runId);

        String stopped = api.runJson(runId);
        assertThat(JsonPath.<String>read(stopped, "$.status")).isEqualTo("SAFE_STOPPED");
        assertThat(JsonPath.<Boolean>read(stopped, "$.recoverable")).isTrue();
        assertThat(JsonPath.<String>read(stopped, "$.statusReason")).contains("SECURITY");
        assertThat(api.stageField(runId, "TEST", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.stageField(runId, "DOCS", "status")).isEqualTo("SUCCEEDED");
        int eventsBefore = api.events(runId).size();

        api.resume(runId, "AGENT", "claude-code").andExpect(status().isBadRequest());
        assertThat(api.status(runId)).isEqualTo("SAFE_STOPPED");

        api.resume(runId, "HUMAN", "candidate").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        assertThat(api.stageField(runId, "TEST", "attempts")).isEqualTo(1);   // never re-executed
        assertThat(api.stageField(runId, "DOCS", "attempts")).isEqualTo(1);
        assertThat(api.stageField(runId, "SECURITY", "attempts")).isEqualTo(4); // only the failed branch re-ran
        assertThat(api.stageField(runId, "SECURITY", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.events(runId).size()).isGreaterThan(eventsBefore);       // history kept, appended to
        assertThat(api.eventTypes(runId)).contains("SAFE_STOPPED", "RUN_RESUMED");
        assertThat(api.decisionTypes(runId)).contains("RESUME");
    }

    @Test
    void resumeIsRefusedForNonRecoverableAndForRunsThatAreNotSafeStopped() throws Exception {
        UUID nonRecoverable = api.createRunWithFaults(SCN_A, List.of(fault("TEST", "COMPENSATION_FAILURE", 1)));
        api.throughValidation(nonRecoverable);
        assertThat(api.status(nonRecoverable)).isEqualTo("SAFE_STOPPED");
        api.resume(nonRecoverable, "HUMAN", "candidate").andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
        assertThat(api.decisionTypes(nonRecoverable)).contains("DECISION_REFUSED");

        UUID waiting = api.createRun(SCN_A);
        api.resume(waiting, "HUMAN", "candidate").andExpect(status().isConflict());

        UUID completed = api.createRun(SCN_A);
        api.throughValidation(completed);
        api.approveRelease(completed, 1, List.of("fixture")).andExpect(status().isOk());
        assertThat(api.status(completed)).isEqualTo("COMPLETED");
        api.resume(completed, "HUMAN", "candidate").andExpect(status().isConflict());
    }
}
