package com.agentic.shortener.scenario;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
 * T069: SCN-A end to end through the API (FR-SCN-001, SC-001, SC-002). Decisions and evidence here are
 * labelled TEST FIXTURES; live evidence comes only from the running system (T078).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioATest {

    private static final List<String> PARALLEL = List.of("TEST", "DOCS", "SECURITY");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private LinkService links;

    @Test
    void greenfieldRunCompletesThroughParallelValidationJoinAndRelease() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        assertThat(api.stageStatus(runId, "CLARIFICATION")).isEqualTo("SKIPPED");
        assertThat(api.stageStatus(runId, "IMPACT_ANALYSIS")).isEqualTo("SKIPPED");
        assertThat(api.stageStatus(runId, "TEST")).isEqualTo("PENDING"); // nothing beyond the gate has started

        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
        assertThat(api.stageStatus(runId, "TEST")).isEqualTo("PENDING");

        api.fixtureEvidence(runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.pendingAction").value("APPROVE:RELEASE_APPROVAL"));

        String run = api.runJson(runId);
        for (String node : PARALLEL) {
            assertThat(api.stageStatus(runId, node)).isEqualTo("SUCCEEDED");
            assertThat(provenance(run, node)).isEqualTo("ACTUAL");
        }
        assertThat(api.stageStatus(runId, "RELEASE_READINESS")).isEqualTo("SUCCEEDED");
        List<String> threads = PARALLEL.stream().map(n -> stageField(run, n, "threadName")).toList();
        assertThat(threads).doesNotHaveDuplicates().allMatch(t -> t.startsWith("stage-worker-"));

        // Same wave: all three were claimed (STAGE_STARTED) before any of them completed.
        List<Map<String, Object>> events = JsonPath.read(api.fetch(runId, "/events").andReturn().getResponse()
                .getContentAsString(), "$[*]");
        int lastStart = PARALLEL.stream().mapToInt(n -> seqOf(events, "STAGE_STARTED", n)).max().orElseThrow();
        int firstEnd = PARALLEL.stream().mapToInt(n -> seqOf(events, "STAGE_SUCCEEDED", n)).min().orElseThrow();
        assertThat(lastStart).isLessThan(firstEnd);

        // Join: readiness started only after every parallel branch ended (SC-002, second half).
        Instant readinessStart = Instant.parse(stageField(run, "RELEASE_READINESS", "startedAt"));
        PARALLEL.forEach(n -> assertThat(Instant.parse(stageField(run, n, "endedAt"))).isBeforeOrEqualTo(readinessStart));

        api.approveRelease(runId, 1, List.of("host names resolving to private addresses are not blocked"))
                .andExpect(status().isOk());

        assertThat(api.status(runId)).isEqualTo("COMPLETED");
        api.fetch(runId, "/report").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASE_APPROVED"));
        assertThat(links.countProbeLinks(runId)).isZero();
        assertThat(api.decisionTypes(runId)).containsSubsequence("APPROVAL", "IMPLEMENTATION_EVIDENCE", "APPROVAL");
    }

    private static String provenance(String run, String node) {
        return stageField(run, node, "provenance");
    }

    private static String stageField(String run, String node, String field) {
        List<String> values = JsonPath.read(run, "$.stages[?(@.node == '" + node + "')]." + field);
        return values.get(0);
    }

    private static int seqOf(List<Map<String, Object>> events, String type, String node) {
        return events.stream().filter(e -> type.equals(e.get("type")) && node.equals(e.get("node")))
                .map(e -> ((Number) e.get("seq")).intValue()).findFirst().orElseThrow();
    }
}
