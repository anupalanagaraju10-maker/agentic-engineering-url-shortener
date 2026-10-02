package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
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
 * T079 (CHK038, FR-REL-011, SC-002/CHK027): with fault injection enabled by test configuration, fault plans
 * are accepted for automated nodes only and every injected effect is labelled; an injected DELAY on the real
 * TEST, DOCS and SECURITY executors makes their concurrent execution measurable.
 */
@SpringBootTest(properties = "workflow.fault-injection.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaultInjectionConfigTest {

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void aFaultPlanIsAcceptedWhenEnabled() throws Exception {
        api.createWithFaults(SCN_A, List.of(fault("INTAKE", "TRANSIENT", 1))).andExpect(status().isCreated());
    }

    @Test
    void faultsAreAllowedOnAutomatedNodesOnly() throws Exception {
        for (String node : List.of("DESIGN_APPROVAL", "IMPLEMENT", "RELEASE_APPROVAL", "CLARIFICATION")) {
            api.createWithFaults(SCN_A, List.of(fault(node, "TRANSIENT", 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.category").value("VALIDATION"));
        }
        api.createWithFaults(SCN_A, List.of(fault("DOCS", "EXPLODE", 1))).andExpect(status().isBadRequest());
        api.createWithFaults(SCN_A, List.of(fault("DOCS", "TRANSIENT", 0))).andExpect(status().isBadRequest());
    }

    @Test
    void injectedEffectsAreLabelledAndOrdinaryEventsAreNot() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("INTAKE", "TRANSIENT", 1)));

        List<Map<String, Object>> events = api.events(runId);
        assertThat(events).filteredOn(e -> "STAGE_FAILED".equals(e.get("type")))
                .singleElement().satisfies(e -> {
                    assertThat(e.get("node")).isEqualTo("INTAKE");
                    assertThat(e.get("injected")).isEqualTo(true);
                });
        assertThat(events).filteredOn(e -> "ATTEMPT_ROLLED_BACK".equals(e.get("type")))
                .allSatisfy(e -> assertThat(e.get("injected")).isEqualTo(true));
        assertThat(events).filteredOn(e -> "RUN_CREATED".equals(e.get("type")))
                .allSatisfy(e -> assertThat(e.get("injected")).isEqualTo(false));
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void injectedDelayMakesTheRealParallelGroupMeasurablyOverlap() throws Exception {
        List<Map<String, Object>> delays = List.of("TEST", "DOCS", "SECURITY").stream()
                .map(n -> Map.<String, Object>of("stage", n, "type", "DELAY", "times", 1, "delayMs", 150)).toList();
        UUID runId = api.createRunWithFaults(SCN_A, delays);

        api.throughValidation(runId);

        List<String> group = List.of("TEST", "DOCS", "SECURITY");
        Instant maxStart = group.stream().map(n -> instant(runId, n, "startedAt")).max(Instant::compareTo).orElseThrow();
        Instant minEnd = group.stream().map(n -> instant(runId, n, "endedAt")).min(Instant::compareTo).orElseThrow();
        assertThat(maxStart).as("max start < min end: the three intervals overlap").isBefore(minEnd);
        List<Object> threads = group.stream().map(n -> field(runId, n, "threadName")).toList();
        assertThat(threads).doesNotHaveDuplicates();
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    private Instant instant(UUID runId, String node, String name) {
        return Instant.parse(String.valueOf(field(runId, node, name)));
    }

    private Object field(UUID runId, String node, String name) {
        try {
            return api.stageField(runId, node, name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
