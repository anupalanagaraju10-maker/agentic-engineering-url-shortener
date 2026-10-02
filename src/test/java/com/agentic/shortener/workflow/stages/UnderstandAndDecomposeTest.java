package com.agentic.shortener.workflow.stages;

import static com.agentic.shortener.workflow.stages.StageTestSupport.context;
import static com.agentic.shortener.workflow.stages.StageTestSupport.outputOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.AmbiguityRules;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T032: UNDERSTAND (normalization, capabilities, change type per research R6) and DECOMPOSE. */
class UnderstandAndDecomposeTest {

    private static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the original "
            + "address, and record redirect count and last redirect time.";
    private static final String SCN_B = "Add optional expiration to existing links; expired links return an expired "
            + "result distinct from not-found.";
    private static final String SCN_C_CLARIFIED = "Make links expire. Expiration is optional per link: the client may "
            + "supply an absolute expiration time when creating a link; existing links are unaffected.";

    private final AmbiguityRules rules = new AmbiguityRules();

    private Map<String, Object> understand(String requirement, CapabilityRegistry registry) {
        return outputOf(new UnderstandExecutor(registry, rules).execute(context(requirement, Map.of())));
    }

    @Test
    void normalizesAndMapsCapabilities() {
        Map<String, Object> out = understand(SCN_A, CapabilityRegistry.withImplemented());
        assertThat(out.get("normalized")).isEqualTo(AmbiguityRules.normalize(SCN_A));
        assertThat((List<Object>) out.get("capabilities")).containsExactly("CREATE_LINK", "REDIRECT", "ANALYTICS");
        assertThat((List<Object>) out.get("findings")).isEmpty();
    }

    @Test
    void changeTypeFollowsResearchR6() {
        CapabilityRegistry allPlanned = CapabilityRegistry.withImplemented();
        assertThat(understand(SCN_A, allPlanned).get("changeType")).isEqualTo("GREENFIELD");
        assertThat(understand(SCN_B, allPlanned).get("changeType")).isEqualTo("BROWNFIELD");
        assertThat(understand(SCN_C_CLARIFIED, allPlanned).get("changeType")).isEqualTo("BROWNFIELD");

        String changeVerb = "Change the redirect count reporting.";
        assertThat(understand(changeVerb, allPlanned).get("changeType")).isEqualTo("GREENFIELD");
        assertThat(understand(changeVerb, CapabilityRegistry.withImplemented(Capability.ANALYTICS)).get("changeType"))
                .isEqualTo("BROWNFIELD");
    }

    @Test
    void ambiguityFindingsAreReported() {
        Map<String, Object> out = understand("Make links expire.", CapabilityRegistry.withImplemented());
        assertThat((List<Object>) out.get("findings")).hasSize(2);
    }

    @Test
    void decomposeProducesTasksWithAcceptanceChecksAndRequirementIds() {
        CapabilityRegistry registry = CapabilityRegistry.withImplemented();
        Map<String, Object> understood = understand(SCN_A, registry);

        Map<String, Object> out = outputOf(new DecomposeExecutor(registry)
                .execute(context(SCN_A, Map.of(Node.UNDERSTAND, understood))));

        List<?> tasks = (List<Object>) out.get("tasks");
        assertThat(tasks).hasSize(3);
        assertThat(tasks).allSatisfy(t -> {
            Map<?, ?> task = (Map<?, ?>) t;
            assertThat(task.get("id")).isNotNull();
            assertThat(task.get("capability")).isNotNull();
            assertThat((List<Object>) task.get("acceptanceChecks")).isNotEmpty();
            assertThat((List<Object>) task.get("requirementIds")).isNotEmpty();
        });
        assertThat((List<Object>) out.get("requirementIds")).contains("FR-URL-001", "FR-URL-006", "FR-URL-010")
                .doesNotHaveDuplicates();
    }

    @Test
    void unknownCapabilityIsPermanentInvalidInput() {
        CapabilityRegistry registry = CapabilityRegistry.withImplemented();
        String requirement = "Return a greeting to every caller.";
        Map<String, Object> understood = understand(requirement, registry);

        StageResult result = new DecomposeExecutor(registry)
                .execute(context(requirement, Map.of(Node.UNDERSTAND, understood)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(result.failureCode()).isEqualTo("INVALID_INPUT");
    }
}
