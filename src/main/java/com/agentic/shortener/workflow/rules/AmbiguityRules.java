package com.agentic.shortener.workflow.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic material-ambiguity rules AMB-R1..R4, implemented exactly as research R5 (FR-ORC-016,
 * CHK011). Only the term lists and patterns below are used; there is no other interpretation.
 */
@Component
public class AmbiguityRules {

    static final List<String> OUTCOME_VERBS = List.of("return", "returns", "respond", "responds", "redirect",
            "redirects", "record", "records", "reject", "rejects", "refuse", "refuses", "create", "creates", "store",
            "stores", "show", "shows", "log", "logs", "block", "blocks", "allow", "allows");

    static final List<String> R2_CLIENT_MARKERS = List.of("optional", "per link", "client may supply",
            "client supplies", "supplied by the client");
    static final List<String> R2_ABSOLUTE_TIME = List.of("expiration time", "expiry time", "expires at", "expiresat",
            "absolute time", "date");
    public static final Pattern DURATION = Pattern.compile("\\b\\d+ (second|minute|hour|day|week|month|year)s?\\b");
    public static final Pattern TRIGGER = Pattern.compile("\\bafter \\d+ (click|redirect|visit)s?\\b");

    static final Pattern UNIVERSAL_EXPIRE = Pattern.compile("\\b(all|every|each) links? (must |will |should )?expire");
    static final Pattern UNIVERSAL_NEVER_EXPIRE =
            Pattern.compile("\\b(all|every|each|no) links? (must |will |should )?(never|not) expire");
    static final List<String> DUPLICATES_ALLOWED = List.of("allow duplicates", "always create a new link");
    static final List<String> DUPLICATES_FORBIDDEN = List.of("never create duplicates", "deduplicate");

    static final List<String> R4_CHANGE_VERBS = List.of("make", "add", "change", "modify", "update", "apply", "enable",
            "require");
    static final List<String> R4_QUALIFIERS = List.of("new", "newly created", "existing", "all", "every", "each",
            "per link", "optional", "specific", "selected", "links created after", "links created before");

    /** Lowercase; '-' and '_' become spaces; other punctuation stripped; whitespace collapsed (research R5). */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        return lower.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Whole-word / whole-phrase match on normalized text. */
    public static boolean containsTerm(String normalized, String term) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])").matcher(normalized).find();
    }

    public static List<String> matchedTerms(String normalized, List<String> terms) {
        return terms.stream().filter(t -> containsTerm(normalized, t)).toList();
    }

    /** Capabilities whose R5 vocabulary appears in the normalized text, in registry order. */
    public static List<Capability> capabilities(String normalized) {
        List<Capability> found = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            if (!matchedTerms(normalized, CapabilityRegistry.vocabulary(capability)).isEmpty()) {
                found.add(capability);
            }
        }
        return found;
    }

    public List<AmbiguityFinding> evaluate(String requirement) {
        String text = normalize(requirement);
        List<AmbiguityFinding> findings = new ArrayList<>();
        List<Capability> capabilities = capabilities(text);

        if (capabilities.isEmpty() && matchedTerms(text, OUTCOME_VERBS).isEmpty()) {
            findings.add(new AmbiguityFinding("AMB-R1", "no capability term and no outcome verb",
                    "The intended observable outcome is unclear: no known capability and no outcome verb."));
        }

        if (capabilities.contains(Capability.EXPIRATION) && !hasExpirationParameter(text)) {
            findings.add(new AmbiguityFinding("AMB-R2",
                    String.join(", ", matchedTerms(text, CapabilityRegistry.vocabulary(Capability.EXPIRATION))),
                    "Expiration is requested without a duration, absolute time, trigger, or a statement that the "
                            + "client supplies it."));
        }

        String conflict = conflict(text);
        if (conflict != null) {
            findings.add(new AmbiguityFinding("AMB-R3", conflict,
                    "Two parts of the requirement conflict with each other."));
        }

        List<String> changeVerbs = matchedTerms(text, R4_CHANGE_VERBS);
        if (!changeVerbs.isEmpty() && containsTerm(text, "links") && matchedTerms(text, R4_QUALIFIERS).isEmpty()) {
            findings.add(new AmbiguityFinding("AMB-R4", changeVerbs.get(0) + " + links",
                    "The requirement changes links without saying which links (new, existing, all, per link)."));
        }
        return findings;
    }

    private static boolean hasExpirationParameter(String text) {
        return !matchedTerms(text, R2_CLIENT_MARKERS).isEmpty() || !matchedTerms(text, R2_ABSOLUTE_TIME).isEmpty()
                || DURATION.matcher(text).find() || TRIGGER.matcher(text).find();
    }

    private static String conflict(String text) {
        Matcher expire = UNIVERSAL_EXPIRE.matcher(text);
        Matcher never = UNIVERSAL_NEVER_EXPIRE.matcher(text);
        if (expire.find() && never.find()) {
            return expire.group() + " / " + never.group();
        }
        List<String> allowed = matchedTerms(text, DUPLICATES_ALLOWED);
        List<String> forbidden = matchedTerms(text, DUPLICATES_FORBIDDEN);
        if (!allowed.isEmpty() && !forbidden.isEmpty()) {
            return allowed.get(0) + " / " + forbidden.get(0);
        }
        if (containsTerm(text, "temporary redirect") && containsTerm(text, "permanent redirect")) {
            return "temporary redirect / permanent redirect";
        }
        return null;
    }
}
