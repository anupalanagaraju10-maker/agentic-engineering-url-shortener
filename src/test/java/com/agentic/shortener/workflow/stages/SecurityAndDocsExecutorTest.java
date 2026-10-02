package com.agentic.shortener.workflow.stages;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.link.UrlValidator;
import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T066: SECURITY probes the real validator with unsafe inputs (FR-URL-003/016, NFR-001); DOCS builds every
 * required section from design, registry and evidence (provenance ACTUAL). Both are part of the parallel group
 * (FR-ORC-004).
 */
class SecurityAndDocsExecutorTest {

    private final SecurityExecutor security = new SecurityExecutor(new UrlValidator());
    private final DocsExecutor docs = new DocsExecutor(new CapabilityRegistry());

    @Test
    void securityRejectsEveryUnsafeInputAndAcceptsTheSafeControl() {
        StageResult result = security.execute(StageTestSupport.context("fixture", Map.of()));

        Map<String, Object> output = StageTestSupport.outputOf(result);
        assertThat(result.provenance()).isEqualTo(Provenance.ACTUAL);
        List<Map<String, Object>> checks = checks(output);
        assertThat(checks).hasSizeGreaterThanOrEqualTo(15);
        assertThat(checks).extracting(c -> c.get("input")).contains("javascript:alert(1)", "file:///etc/passwd",
                "http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/", "http://192.168.1.1/",
                "http://169.254.169.254/", "http://[::1]/", "http://[fc00::1]/", "http://[::ffff:127.0.0.1]/",
                "http://2130706433/", "https://example.com/");
        assertThat(checks).allSatisfy(c -> assertThat(c.get("passed")).isEqualTo(true));
        assertThat(output.get("unsafeRejected")).isEqualTo(output.get("unsafeTotal"));
        assertThat(security.checkExit(output)).isEmpty();
    }

    @Test
    void anAcceptedUnsafeInputIsAPermanentImplementationDefect() {
        UrlValidator permissive = new UrlValidator() {
            @Override
            public void validate(String url) {
                if (!url.contains("localhost")) {
                    super.validate(url);
                }
            }
        };

        StageResult result = new SecurityExecutor(permissive).execute(StageTestSupport.context("fixture", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(result.failureCode()).isEqualTo("IMPLEMENTATION_DEFECT");
        assertThat(result.failureReason()).contains("http://localhost/");
    }

    @Test
    void docsContainEveryRequiredSectionBuiltFromDesignRegistryAndEvidence() {
        StageResult result = docs.execute(StageTestSupport.context("fixture", upstream()));

        Map<String, Object> output = StageTestSupport.outputOf(result);
        assertThat(result.provenance()).isEqualTo(Provenance.ACTUAL);
        @SuppressWarnings("unchecked")
        Map<String, String> sections = (Map<String, String>) output.get("sections");
        assertThat(sections.keySet()).containsExactlyElementsOf(DocsExecutor.REQUIRED_SECTIONS);
        assertThat(sections.values()).allSatisfy(text -> assertThat(text).isNotBlank());
        assertThat(sections.get("Overview")).contains("Create a short link");
        assertThat(sections.get("Behavior")).contains("CREATE_LINK B2").contains("javascript");
        assertThat(sections.get("API")).contains("POST /api/links").contains("GET /r/{code}");
        assertThat(sections.get("Data")).contains("V2__links.sql");
        assertThat(sections.get("Implementation")).contains("8c1eeff").contains("UrlValidator");
        assertThat(sections.get("Traceability")).contains("FR-URL-001").contains("TASK-1");
        assertThat(sections.get("Limitations")).contains("not blocked");
        assertThat(String.valueOf(output.get("markdown"))).startsWith("# ").contains("## Behavior");
        assertThat(docs.checkExit(output)).isEmpty();
    }

    @Test
    void docsExitConditionRejectsAMissingSection() {
        assertThat(docs.checkExit(Map.of("sections", Map.of("Overview", "x")))).isPresent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("checks");
    }

    private static Map<Node, Map<String, Object>> upstream() {
        Map<Node, Map<String, Object>> upstream = new EnumMap<>(Node.class);
        upstream.put(Node.UNDERSTAND, Map.of("requirement", "Create a short link for a valid HTTP/HTTPS address.",
                "normalized", "create a short link for a valid http/https address",
                "capabilities", List.of("CREATE_LINK", "REDIRECT", "ANALYTICS")));
        upstream.put(Node.DECOMPOSE, Map.of("tasks", List.of(
                Map.of("id", "TASK-1", "capability", "CREATE_LINK", "requirementIds", List.of("FR-URL-001"),
                        "acceptanceChecks", List.of("probe.create-link")))));
        upstream.put(Node.DESIGN, Map.of(
                "components", List.of("LinkController", "LinkService", "UrlValidator"),
                "interfaceChanges", List.of("POST /api/links", "GET /r/{code}"),
                "dataChanges", List.of("link table (V2__links.sql)"),
                "testPlan", List.of("LinkApiTest", "probe.create-link"),
                "requirementIds", List.of("FR-URL-001", "FR-URL-006"),
                "securitySensitive", true));
        upstream.put(Node.IMPLEMENT, Map.of("revision", "8c1eeff", "summary", "fixture",
                "changedArtifacts", List.of("src/main/java/com/agentic/shortener/link/UrlValidator.java"),
                "coveredComponents", List.of("UrlValidator"),
                "uncoveredComponents", List.of("LinkController", "LinkService")));
        return upstream;
    }
}
