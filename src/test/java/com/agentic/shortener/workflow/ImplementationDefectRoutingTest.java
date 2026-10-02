package com.agentic.shortener.workflow;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T068 (CHK036): a TEST probe failing after accepted evidence is an IMPLEMENTATION_DEFECT: the run waits in
 * AWAITING_REWORK (pendingAction REWORK_OR_TERMINATE) instead of failing; a HUMAN terminate then ends it
 * FAILED. Rework itself is Phase 7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImplementationDefectRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private LinkService links;

    @Test
    void defectAfterEvidenceWaitsForReworkAndCanBeTerminated() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        doReturn("https://wrong.example.com/").when(links).redirect(anyString()); // broken build (fixture)

        api.fixtureEvidence(runId).andExpect(status().isOk());

        String run = api.runJson(runId);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("AWAITING_REWORK");
        assertThat(JsonPath.<String>read(run, "$.pendingAction")).isEqualTo("REWORK_OR_TERMINATE");
        assertThat(api.stageStatus(runId, "TEST")).isEqualTo("FAILED");
        List<String> codes = JsonPath.read(run, "$.stages[?(@.node == 'TEST')].failureReason");
        assertThat(codes.get(0)).contains("probe.redirect");
        assertThat(api.stageStatus(runId, "RELEASE_READINESS")).isEqualTo("PENDING");
        // T085: the defect opens an incident whose recovery mechanism is REWORK
        List<Map<String, Object>> events = api.events(runId);
        Map<String, Object> detected = events.stream().filter(e -> "FAILURE_DETECTED".equals(e.get("type")))
                .filter(e -> "TEST".equals(((Map<?, ?>) e.get("payload")).get("node"))).findFirst().orElseThrow();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.get("type")).isEqualTo("RECOVERY_STARTED");
            assertThat(((Map<?, ?>) e.get("payload")).get("mechanism")).isEqualTo("REWORK");
            assertThat(((Map<?, ?>) e.get("payload")).get("incidentId")).isEqualTo(detected.get("seq"));
        });
        assertThat(links.countProbeLinks(runId)).isZero();

        api.terminate(runId).andExpect(status().isOk());
        assertThat(api.status(runId)).isEqualTo("FAILED");
        assertThat(api.eventTypes(runId)).contains("RECOVERY_FAILED"); // the rework incident closes unrecovered
    }
}
