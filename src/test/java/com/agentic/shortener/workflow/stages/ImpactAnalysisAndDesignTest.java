package com.agentic.shortener.workflow.stages;

import static com.agentic.shortener.workflow.stages.StageTestSupport.context;
import static com.agentic.shortener.workflow.stages.StageTestSupport.outputOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.rules.AmbiguityRules;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T033: IMPACT_ANALYSIS (10 areas, FR-SCN-002) and DESIGN outputs (research R6). */
class ImpactAnalysisAndDesignTest {

    private static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the original "
            + "address, and record redirect count and last redirect time.";
    private static final String SCN_B = "Add optional expiration to existing links; expired links return an expired "
            + "result distinct from not-found.";
    private static final List<String> TEN_AREAS = List.of("currentBehavior", "requestedBehavior", "affectedComponents",
            "interfaces", "data", "tests", "documentation", "regressionRisks", "securityReliabilityImpact",
            "rollbackCompensation");

    private final AmbiguityRules rules = new AmbiguityRules();

    private Map<Node, Map<String, Object>> upstream(String requirement, CapabilityRegistry registry, boolean impact) {
        Map<Node, Map<String, Object>> up = new EnumMap<>(Node.class);
        up.put(Node.UNDERSTAND, outputOf(new UnderstandExecutor(registry, rules).execute(context(requirement, up))));
        up.put(Node.DECOMPOSE, outputOf(new DecomposeExecutor(registry).execute(context(requirement, up))));
        if (impact) {
            up.put(Node.IMPACT_ANALYSIS, outputOf(new ImpactAnalysisExecutor(registry).execute(context(requirement, up))));
        }
        return up;
    }

    private Map<String, Object> design(String requirement, CapabilityRegistry registry, boolean impact) {
        return outputOf(new DesignExecutor(registry).execute(context(requirement, upstream(requirement, registry, impact))));
    }

    @Test
    void impactReportPopulatesAllTenAreas() {
        CapabilityRegistry registry = CapabilityRegistry.withImplemented(Capability.CREATE_LINK, Capability.REDIRECT,
                Capability.ANALYTICS, Capability.IDEMPOTENCY);
        Map<String, Object> report = upstream(SCN_B, registry, true).get(Node.IMPACT_ANALYSIS);

        assertThat(report).containsKeys(TEN_AREAS.toArray(String[]::new));
        for (String area : TEN_AREAS) {
            assertThat(report.get(area)).as(area).isNotNull().asString().isNotBlank().isNotEqualTo("[]");
        }
        assertThat(report.get("affectedComponents").toString()).contains("LinkService");
        assertThat(report.get("currentBehavior").toString()).contains("redirects to its original address");
    }

    @Test
    void designListsEveryRequiredElement() {
        Map<String, Object> design = design(SCN_A, CapabilityRegistry.withImplemented(), false);

        assertThat(design).containsKeys("components", "interfaceChanges", "dataChanges", "testPlan", "dependencies",
                "securitySensitive", "requirementIds", "implementationRequired", "changesApprovedRequirements",
                "taskComponents");
        assertThat((List<Object>) design.get("components")).contains("LinkService", "RedirectController");
        assertThat((List<Object>) design.get("requirementIds")).contains("FR-URL-001", "FR-URL-006", "FR-URL-010");
        assertThat(design.get("implementationRequired")).isEqualTo(true); // capabilities are PLANNED
        assertThat(design.get("securitySensitive")).isEqualTo(true);       // CREATE_LINK touches URL validation
        assertThat((List<Object>) design.get("dependencies")).isEmpty();
        assertThat((List<Object>) design.get("changesApprovedRequirements")).isEmpty();
    }

    @Test
    void noImplementationRequiredOnlyWhenWithinRecordedBehavior() {
        CapabilityRegistry implemented = CapabilityRegistry.withImplemented(Capability.values());
        String withinRecord = "Make links expire. Expiration is optional per link: the client may supply an absolute "
                + "expiration time when creating a link; existing links are unaffected.";
        assertThat(design(withinRecord, implemented, true).get("implementationRequired")).isEqualTo(false);

        String outOfRecord = withinRecord + " Links get a default expiration.";
        assertThat(design(outOfRecord, implemented, true).get("implementationRequired")).isEqualTo(true);

        String changeVerb = "Change the expiration of existing links to be optional per link.";
        assertThat(design(changeVerb, implemented, true).get("implementationRequired")).isEqualTo(true);
    }

    @Test
    void contradictionOfApprovedBehaviorIsListed() {
        CapabilityRegistry implemented = CapabilityRegistry.withImplemented(Capability.values());
        Map<String, Object> design = design("All links expire after 30 days for existing links.", implemented, true);
        assertThat((List<Object>) design.get("changesApprovedRequirements")).contains("FR-URL-008");
    }
}
