package com.agentic.shortener.workflow.rules;

import static com.agentic.shortener.workflow.rules.AmbiguityRules.containsTerm;
import static com.agentic.shortener.workflow.rules.AmbiguityRules.matchedTerms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Research R6 rules over the normalized requirement: brownfield classification,
 * {@code implementationRequired}, and contradictions of approved requirements (CHK012, CHK034).
 */
public final class RecordedBehaviorRules {

    public static final List<String> BEHAVIOR_CHANGE_VERBS = List.of("add", "change", "modify", "replace", "remove",
            "extend", "introduce", "increase", "decrease", "convert", "migrate");

    public static final List<String> OUT_OF_RECORD_DETAILS = List.of("default", "maximum", "max", "minimum", "min",
            "renew", "notify", "delete", "purge", "archive");

    /** Contradiction patterns of recorded statements, mapped to the approved requirement IDs they change. */
    public static final Map<Pattern, List<String>> CONTRADICTIONS = contradictions();

    private RecordedBehaviorRules() {
    }

    /** BROWNFIELD if "existing", or a behavior change verb with at least one IMPLEMENTED matched capability. */
    public static boolean isBrownfield(String normalized, List<Capability> matched, CapabilityRegistry registry) {
        if (containsTerm(normalized, "existing")) {
            return true;
        }
        return !matchedTerms(normalized, BEHAVIOR_CHANGE_VERBS).isEmpty() && matched.stream()
                .anyMatch(c -> registry.entry(c).status() == CapabilityStatus.IMPLEMENTED);
    }

    /**
     * {@code false} only if every matched capability is IMPLEMENTED, there is no behavior change verb, and no
     * out-of-record detail pattern (R2 duration/trigger regexes or the detail words) is present.
     */
    public static boolean implementationRequired(String normalized, List<Capability> matched,
            CapabilityRegistry registry) {
        boolean allImplemented = !matched.isEmpty() && matched.stream()
                .allMatch(c -> registry.entry(c).status() == CapabilityStatus.IMPLEMENTED);
        boolean changeVerb = !matchedTerms(normalized, BEHAVIOR_CHANGE_VERBS).isEmpty();
        boolean outOfRecord = !matchedTerms(normalized, OUT_OF_RECORD_DETAILS).isEmpty()
                || AmbiguityRules.DURATION.matcher(normalized).find() || AmbiguityRules.TRIGGER.matcher(normalized).find();
        return !(allImplemented && !changeVerb && !outOfRecord);
    }

    public static List<String> changesApprovedRequirements(String normalized) {
        List<String> ids = new ArrayList<>();
        CONTRADICTIONS.forEach((pattern, requirementIds) -> {
            if (pattern.matcher(normalized).find()) {
                requirementIds.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
            }
        });
        return ids;
    }

    private static Map<Pattern, List<String>> contradictions() {
        Map<Pattern, List<String>> map = new LinkedHashMap<>();
        // EXPIRATION B4: links without an expiration never expire (FR-URL-008)
        map.put(Pattern.compile("\\bdefault expir"), List.of("FR-URL-008"));
        map.put(Pattern.compile("\\b(all|every|each) links? (must |will |should )?expire"), List.of("FR-URL-008"));
        map.put(Pattern.compile("\\blinks without (an )?expiration (must |will |should )?expire"), List.of("FR-URL-008"));
        // CREATE_LINK B2/B3: unsafe schemes and local/private hosts are refused (FR-URL-003, FR-URL-016)
        map.put(Pattern.compile("\\ballow (javascript|file|data)\\b"), List.of("FR-URL-003"));
        map.put(Pattern.compile("\\ballow localhost\\b"), List.of("FR-URL-016"));
        map.put(Pattern.compile("\\ballow private (ip|address)"), List.of("FR-URL-016"));
        // IDEMPOTENCY B4: no key means a new link (FR-URL-011)
        map.put(Pattern.compile("\\bdeduplicate\\b"), List.of("FR-URL-011"));
        map.put(Pattern.compile("\\bsame url returns the same link\\b"), List.of("FR-URL-011"));
        return java.util.Collections.unmodifiableMap(map);
    }
}
