package com.agentic.shortener.workflow.api;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.engine.RunLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T035: human gates through the API (FR-HUM-001..007, SC-003, CHK002, CHK035, H4, NFR-009). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HumanGateTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RunLocks locks;

    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void noProgressPastAGateWithoutADecision() throws Exception {
        UUID runId = api.createRun(SCN_A);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(api.stageStatus(runId, "DESIGN_APPROVAL")).isEqualTo("BLOCKED");
        assertThat(api.stageStatus(runId, "IMPLEMENT")).isEqualTo("PENDING");
    }

    @Test
    void humanApprovalAdvancesToTheImplementationWait() throws Exception {
        UUID runId = api.createRun(SCN_A);

        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_IMPLEMENTATION"))
                .andExpect(jsonPath("$.pendingAction").value("RECORD_IMPLEMENTATION"));
        assertThat(api.decisionTypes(runId)).contains("APPROVAL");
        assertThat(api.eventTypes(runId)).contains("APPROVAL_GRANTED", "IMPLEMENTATION_REQUESTED");
    }

    @Test
    void nonHumanOrInvalidActorsAreRefusedAndRecorded() throws Exception {
        UUID runId = api.createRun(SCN_A);
        String[][] actors = {{"AGENT", "claude-code"}, {"SYSTEM", "workflow-engine"}, {"HUMAN", " "},
                {"HUMAN", "workflow-engine"}, {"HUMAN", "claude-code"}};
        for (String[] actor : actors) {
            api.gate(runId, "approve", "DESIGN_APPROVAL", actor[0], actor[1], 1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.category").value("VALIDATION"));
        }
        assertThat(api.decisionTypes(runId).stream().filter("DECISION_REFUSED"::equals)).hasSize(actors.length);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(api.stageStatus(runId, "DESIGN_APPROVAL")).isEqualTo("BLOCKED");
    }

    @Test
    void wrongGateOrStalePlanVersionIsRefusedWithoutStateChange() throws Exception {
        UUID runId = api.createRun(SCN_A);

        api.approve(runId, "RELEASE_APPROVAL", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
        api.approve(runId, "DESIGN_APPROVAL", 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));

        assertThat(api.decisionTypes(runId).stream().filter("DECISION_REFUSED"::equals)).hasSize(2);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void rejectionWaitsForReworkOrTermination() throws Exception {
        UUID runId = api.createRun(SCN_A);

        api.gate(runId, "reject", "DESIGN_APPROVAL", "HUMAN", "candidate", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REWORK"))
                .andExpect(jsonPath("$.pendingAction").value("REWORK_OR_TERMINATE"));
        assertThat(api.decisionTypes(runId)).contains("REJECTION");
        assertThat(api.eventTypes(runId)).contains("APPROVAL_REJECTED");
        assertThat(api.stageStatus(runId, "DESIGN_APPROVAL")).isEqualTo("FAILED");
    }

    @Test
    void terminateFromEveryWaitingState() throws Exception {
        UUID awaitingApproval = api.createRun(SCN_A);
        UUID awaitingClarification = api.createRun("Make links expire.");
        UUID awaitingImplementation = api.createRun(SCN_A);
        api.approve(awaitingImplementation, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        UUID awaitingRework = api.createRun(SCN_A);
        api.gate(awaitingRework, "reject", "DESIGN_APPROVAL", "HUMAN", "candidate", 1).andExpect(status().isOk());

        assertThat(api.status(awaitingClarification)).isEqualTo("AWAITING_CLARIFICATION");
        for (UUID runId : List.of(awaitingApproval, awaitingClarification, awaitingImplementation, awaitingRework)) {
            api.terminate(runId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
            assertThat(api.decisionTypes(runId)).contains("TERMINATION");
        }
    }

    @Test
    void duplicateConcurrentApprovalOnlyOneSucceeds() throws Exception {
        UUID runId = api.createRun(SCN_A);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(() -> api.approve(runId, "DESIGN_APPROVAL", 1).andReturn().getResponse().getStatus());
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(api.decisionTypes(runId).stream().filter("APPROVAL"::equals)).hasSize(1);
    }

    @Test
    void commandOnABusyRunFailsFastWith409() throws Exception {
        UUID runId = api.createRun(SCN_A);
        int code = locks.withLock(runId, () -> {
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return api.approve(runId, "DESIGN_APPROVAL", 1).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(code).isEqualTo(409);
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        api.approve(UUID.randomUUID(), "DESIGN_APPROVAL", 1).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return Map.of();
    }
}
