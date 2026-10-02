package com.agentic.shortener.workflow.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** T031: deterministic material-ambiguity rules exactly as research R5 (FR-ORC-016, CHK011). */
class AmbiguityRulesTest {

    static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the original address, "
            + "and record redirect count and last redirect time.";
    static final String SCN_B = "Add optional expiration to existing links; expired links return an expired result "
            + "distinct from not-found.";
    static final String SCN_C = "Make links expire.";
    static final String SCN_C_CLARIFIED = SCN_C + " Expiration is optional per link: the client may supply an absolute "
            + "expiration time when creating a link; after that time the link returns the expired result; links "
            + "without an expiration never expire; existing links are unaffected.";

    private final AmbiguityRules rules = new AmbiguityRules();

    @Test
    void normalizationFollowsResearchR5() {
        assertThat(AmbiguityRules.normalize("  Add Short-Links, NOW!! (v2_beta) 30 Days  "))
                .isEqualTo("add short links now v2 beta 30 days");
    }

    @Test
    void clearScenariosHaveNoFindings() {
        assertThat(rules.evaluate(SCN_A)).isEmpty();
        assertThat(rules.evaluate(SCN_B)).isEmpty();
        assertThat(rules.evaluate(SCN_C_CLARIFIED)).isEmpty();
    }

    @Test
    void ambiguousScenarioTriggersMissingParameterAndScope() {
        List<AmbiguityFinding> findings = rules.evaluate(SCN_C);
        assertThat(findings).extracting(AmbiguityFinding::ruleId).containsExactly("AMB-R2", "AMB-R4");
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.matched()).isNotBlank();
            assertThat(f.explanation()).isNotBlank();
        });
    }

    @Test
    void conflictingStatementsTriggerR3() {
        assertThat(rules.evaluate("All links must expire after 30 days and all links must never expire."))
                .extracting(AmbiguityFinding::ruleId).containsExactly("AMB-R3");
        assertThat(rules.evaluate("Use a temporary redirect and a permanent redirect for short links."))
                .extracting(AmbiguityFinding::ruleId).containsExactly("AMB-R3");
    }

    @Test
    void noCapabilityAndNoOutcomeTriggersR1() {
        assertThat(rules.evaluate("Make it better for everyone."))
                .extracting(AmbiguityFinding::ruleId).containsExactly("AMB-R1");
    }

    @Test
    void outcomeVerbWithoutCapabilityIsNotAmbiguous() {
        // R1 does not fire; DECOMPOSE later fails PERMANENT as out-of-vocabulary (CHK005)
        assertThat(rules.evaluate("Return a greeting to every caller.")).isEmpty();
    }

    @Test
    void missingEdgeCaseDetailAloneIsNotAmbiguity() {
        assertThat(rules.evaluate("Redirect each short link to its original address.")).isEmpty();
    }

    @Test
    void anyDocumentedParameterSatisfiesR2() {
        assertThat(rules.evaluate("Every new link should expire after 30 days.")).isEmpty();
        assertThat(rules.evaluate("New links expire after 100 clicks.")).isEmpty();
        assertThat(rules.evaluate("New links expire at an absolute time chosen by the client.")).isEmpty();
    }
}
