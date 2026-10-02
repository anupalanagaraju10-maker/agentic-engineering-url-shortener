package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.agentic.shortener.workflow.stages.DocsExecutor;
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
 * T081 (FR-REL-005, FR-ORC-014): after retries are exhausted DOCS falls back to a minimal template that still
 * has every required section, labelled FALLBACK; SECURITY never falls back.
 */
@SpringBootTest(properties = "workflow.fault-injection.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FallbackTest {

    @Autowired
    private MockMvc mvc;
    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    @Test
    void docsFallsBackToTheMinimalTemplateAfterExhaustedRetries() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("DOCS", "TRANSIENT", 3)));
        api.throughValidation(runId);

        assertThat(api.stageField(runId, "DOCS", "status")).isEqualTo("SUCCEEDED");
        assertThat(api.stageField(runId, "DOCS", "provenance")).isEqualTo("FALLBACK");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) api.stageField(runId, "DOCS", "output");
        @SuppressWarnings("unchecked")
        Map<String, String> sections = (Map<String, String>) output.get("sections");
        assertThat(sections.keySet()).containsExactlyElementsOf(DocsExecutor.REQUIRED_SECTIONS);
        assertThat(sections.values()).allSatisfy(text -> assertThat(text).isNotBlank());
        assertThat(api.eventTypes(runId)).containsSubsequence("RETRY_EXHAUSTED", "FALLBACK_USED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL"); // readiness accepted the FALLBACK docs
    }

    @Test
    void securityNeverFallsBack() throws Exception {
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("SECURITY", "TRANSIENT", 3)));
        api.throughValidation(runId);

        assertThat(api.eventTypes(runId)).doesNotContain("FALLBACK_USED");
        assertThat(api.stageField(runId, "SECURITY", "status")).isEqualTo("PENDING");
        assertThat(api.status(runId)).isEqualTo("SAFE_STOPPED");
    }
}
