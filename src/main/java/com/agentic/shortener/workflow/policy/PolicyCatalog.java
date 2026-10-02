package com.agentic.shortener.workflow.policy;

import static com.agentic.shortener.workflow.rules.AmbiguityRules.containsTerm;
import static com.agentic.shortener.workflow.rules.AmbiguityRules.matchedTerms;

import com.agentic.shortener.workflow.engine.Node;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Policy v1: five mandatory checks, one per FR-POL-002 domain (research R11, ADR-0004 §7). All checks are
 * pure functions over normalized text or stage outputs; there is no policy engine.
 */
public final class PolicyCatalog {

    public static final String VERSION = "v1";

    public record CheckDefinition(String id, String domain, Node evaluatedAfter, boolean mandatory) {
    }

    public static final List<CheckDefinition> CHECKS = List.of(
            new CheckDefinition("PRIV-01", "privacy", Node.UNDERSTAND, true),
            new CheckDefinition("SEC-01", "security", Node.DESIGN, true),
            new CheckDefinition("CHG-01", "change-control", Node.DESIGN, true),
            new CheckDefinition("DEP-01", "dependencies", Node.DESIGN, true),
            new CheckDefinition("AUD-01", "audit", Node.RELEASE_READINESS, true));

    /** PRIV-01 term list, exactly research R11 (CHK018). */
    public static final List<String> PRIVACY_TERMS = List.of("ip address", "visitor ip", "client ip", "user agent",
            "email", "e mail", "phone", "location", "geolocation", "geo location", "device id", "cookie",
            "browser fingerprint", "visitor name", "user identity", "personal data", "pii", "track users",
            "user tracking");

    /**
     * SEC-01 patterns: the research R6 CREATE_LINK B2/B3 contradiction patterns plus disabled/skipped
     * validation (documented in research R11 during Phase 3).
     */
    public static final List<Pattern> SECURITY_WEAKENING = List.of(
            Pattern.compile("\\ballow (javascript|file|data)\\b"),
            Pattern.compile("\\ballow localhost\\b"),
            Pattern.compile("\\ballow private (ip|address)"),
            Pattern.compile("\\b(disable|skip) validation\\b"));

    /** DEP-01 approved direct dependencies with licenses verified from POM metadata (research R11). */
    public static final Map<String, String> APPROVED_DEPENDENCIES = orderedMap(
            "org.springframework.boot:spring-boot-starter-parent", "Apache License 2.0",
            "org.springframework.boot:spring-boot-starter-web", "Apache License 2.0",
            "org.springframework.boot:spring-boot-starter-data-jpa", "Apache License 2.0",
            "org.springframework.boot:spring-boot-starter-validation", "Apache License 2.0",
            "org.springframework.boot:spring-boot-starter-actuator", "Apache License 2.0",
            "org.flywaydb:flyway-core", "Apache License 2.0",
            "com.h2database:h2", "MPL 2.0 or EPL 1.0",
            "org.springframework.boot:spring-boot-starter-test", "Apache License 2.0");

    /**
     * Technology mentions a requirement can make; a design "adds" the mapped dependency (documented in
     * research R11 during Phase 3). Unapproved ones map to themselves and fail DEP-01.
     */
    public static final Map<String, String> TECHNOLOGY_MENTIONS = orderedMap(
            "h2", "com.h2database:h2",
            "flyway", "org.flywaydb:flyway-core",
            "jpa", "org.springframework.boot:spring-boot-starter-data-jpa",
            "spring web", "org.springframework.boot:spring-boot-starter-web",
            "bean validation", "org.springframework.boot:spring-boot-starter-validation",
            "actuator", "org.springframework.boot:spring-boot-starter-actuator",
            "redis", "redis",
            "kafka", "kafka",
            "rabbitmq", "rabbitmq",
            "mongodb", "mongodb",
            "postgresql", "postgresql",
            "postgres", "postgres",
            "mysql", "mysql",
            "elasticsearch", "elasticsearch",
            "temporal", "temporal",
            "camunda", "camunda");

    public static final List<String> IMPACT_AREAS = List.of("currentBehavior", "requestedBehavior", "affectedComponents",
            "interfaces", "data", "tests", "documentation", "regressionRisks", "securityReliabilityImpact",
            "rollbackCompensation");

    private PolicyCatalog() {
    }

    public static CheckDefinition check(String id) {
        return CHECKS.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    public static PolicyOutcome priv01(String normalized) {
        List<String> hits = matchedTerms(normalized, PRIVACY_TERMS);
        return hits.isEmpty()
                ? new PolicyOutcome(PolicyResult.PASS, "no personal-data capture requested")
                : new PolicyOutcome(PolicyResult.EXCEPTION_REQUESTED,
                        "requirement asks to capture personal data: " + String.join(", ", hits));
    }

    public static PolicyOutcome sec01(String normalized) {
        for (Pattern pattern : SECURITY_WEAKENING) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.find()) {
                return new PolicyOutcome(PolicyResult.FAIL, "requirement weakens URL safety: '" + matcher.group() + "'");
            }
        }
        return new PolicyOutcome(PolicyResult.PASS, "URL safety controls are not weakened");
    }

    public static PolicyOutcome chg01(String changeType, Map<String, Object> impactAnalysis) {
        if (!"BROWNFIELD".equals(changeType)) {
            return new PolicyOutcome(PolicyResult.NOT_APPLICABLE, "greenfield change: no impact analysis required");
        }
        if (impactAnalysis == null) {
            return new PolicyOutcome(PolicyResult.FAIL, "brownfield change without an impact analysis");
        }
        List<String> missing = IMPACT_AREAS.stream().filter(a -> isBlank(impactAnalysis.get(a))).toList();
        return missing.isEmpty()
                ? new PolicyOutcome(PolicyResult.PASS, "impact analysis covers all ten areas")
                : new PolicyOutcome(PolicyResult.FAIL, "impact analysis is missing: " + String.join(", ", missing));
    }

    public static PolicyOutcome dep01(List<String> addedDependencies) {
        if (addedDependencies == null || addedDependencies.isEmpty()) {
            return new PolicyOutcome(PolicyResult.NOT_APPLICABLE, "the design adds no dependencies");
        }
        List<String> unapproved = addedDependencies.stream().filter(d -> !APPROVED_DEPENDENCIES.containsKey(d)).toList();
        return unapproved.isEmpty()
                ? new PolicyOutcome(PolicyResult.PASS, "all added dependencies are approved: " + addedDependencies)
                : new PolicyOutcome(PolicyResult.FAIL, "unapproved dependencies: " + unapproved);
    }

    /** Dependencies a design adds: the technology mentions found in the normalized requirement. */
    public static List<String> mentionedDependencies(String normalized) {
        return TECHNOLOGY_MENTIONS.entrySet().stream().filter(e -> containsTerm(normalized, e.getKey()))
                .map(Map.Entry::getValue).distinct().toList();
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank() || "[]".equals(value.toString());
    }

    private static Map<String, String> orderedMap(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
