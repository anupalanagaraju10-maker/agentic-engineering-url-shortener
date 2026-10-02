package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
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
 * T085 (FR-OBS-003): recovery incidents are derived from audit events. FAILURE_DETECTED opens an incident (its
 * seq is the incident id), RECOVERY_STARTED names the mechanism, RECOVERY_COMPLETED / RECOVERY_FAILED close it.
 */
@SpringBootTest(properties = "workflow.fault-injection.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentEventsTest {

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void retriedFailureIsOneRecoveredIncident() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("UNDERSTAND", "TRANSIENT", 2)));

        List<Map<String, Object>> detected = ofType(runId, "FAILURE_DETECTED");
        assertThat(detected).hasSize(1); // one incident for the whole retry sequence
        int incident = seq(detected.get(0));
        assertThat(payload(detected.get(0))).containsEntry("node", "UNDERSTAND").containsEntry("failureClass", "TRANSIENT");
        assertThat(mechanisms(runId, incident)).containsExactly("RETRY");
        Map<String, Object> completed = single(runId, "RECOVERY_COMPLETED", incident);
        assertThat(((Number) payload(completed).get("durationMs")).longValue()).isNotNegative();
        assertThat(seq(completed)).isGreaterThan(incident);
    }

    @Test
    void fallbackAndCompensationAreRecordedAsMechanisms() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("DOCS", "TRANSIENT", 3),
                fault("TEST", "TRANSIENT", 1)));
        api.throughValidation(runId);

        int docs = incidentFor(runId, "DOCS");
        assertThat(mechanisms(runId, docs)).containsExactly("RETRY", "FALLBACK");
        single(runId, "RECOVERY_COMPLETED", docs);

        int test = incidentFor(runId, "TEST");
        assertThat(mechanisms(runId, test)).contains("RETRY", "COMPENSATION");
        single(runId, "RECOVERY_COMPLETED", test);
    }

    @Test
    void resumeIsTheMechanismThatClosesAnExhaustedIncident() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("SECURITY", "TRANSIENT", 3)));
        api.throughValidation(runId);
        int incident = incidentFor(runId, "SECURITY");
        assertThat(ofType(runId, "RECOVERY_COMPLETED")).noneMatch(e -> incident == incidentId(e));

        api.resume(runId, "HUMAN", "candidate");

        assertThat(mechanisms(runId, incident)).containsExactly("RETRY", "RESUME");
        single(runId, "RECOVERY_COMPLETED", incident);
    }

    @Test
    void anUnrecoveredFailureIsClosedAsFailed() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("DECOMPOSE", "PERMANENT", 1)));

        assertThat(api.status(runId)).isEqualTo("FAILED");
        int incident = incidentFor(runId, "DECOMPOSE");
        single(runId, "RECOVERY_FAILED", incident);
        assertThat(ofType(runId, "RECOVERY_COMPLETED")).isEmpty();
    }

    @Test
    void anInjectedPermanentValidationFailureIsNotADefect() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("SECURITY", "PERMANENT", 1)));
        api.throughValidation(runId);
        // an injected PERMANENT is not a defect in the build: the run fails rather than waiting for rework
        assertThat(api.status(runId)).isEqualTo("FAILED");
        single(runId, "RECOVERY_FAILED", incidentFor(runId, "SECURITY"));
    }

    private int incidentFor(UUID runId, String node) throws Exception {
        return seq(ofType(runId, "FAILURE_DETECTED").stream().filter(e -> node.equals(payload(e).get("node")))
                .findFirst().orElseThrow());
    }

    private List<String> mechanisms(UUID runId, int incident) throws Exception {
        return ofType(runId, "RECOVERY_STARTED").stream().filter(e -> incidentId(e) == incident)
                .map(e -> (String) payload(e).get("mechanism")).distinct().toList();
    }

    private Map<String, Object> single(UUID runId, String type, int incident) throws Exception {
        List<Map<String, Object>> matching = ofType(runId, type).stream().filter(e -> incidentId(e) == incident).toList();
        assertThat(matching).as("%s for incident %s", type, incident).hasSize(1);
        return matching.get(0);
    }

    private List<Map<String, Object>> ofType(UUID runId, String type) throws Exception {
        return api.events(runId).stream().filter(e -> type.equals(e.get("type"))).toList();
    }

    private static int incidentId(Map<String, Object> event) {
        Object id = payload(event).get("incidentId");
        return id == null ? -1 : ((Number) id).intValue();
    }

    private static int seq(Map<String, Object> event) {
        return ((Number) event.get("seq")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }
}
