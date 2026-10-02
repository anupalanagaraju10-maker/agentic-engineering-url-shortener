package com.agentic.shortener.workflow.rules;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Static registry of the URL-shortener capabilities, exactly as research R5 (vocabulary) and R6
 * (requirement IDs and recorded behavior statements), plus the design data used by DESIGN and
 * IMPACT_ANALYSIS. An entry moves from PLANNED to IMPLEMENTED in the same change that implements it:
 * CREATE_LINK, REDIRECT, ANALYTICS and IDEMPOTENCY by Phase 4 (SCN-A, migration V2); EXPIRATION by Phase 8
 * (SCN-B, migration V3).
 */
@Component
public class CapabilityRegistry {

    private static final Map<Capability, List<String>> VOCABULARY = new EnumMap<>(Map.of(
            Capability.CREATE_LINK, List.of("short link", "short links", "short url", "shorten", "create a link",
                    "create link"),
            Capability.REDIRECT, List.of("redirect", "redirects", "redirected"),
            Capability.ANALYTICS, List.of("redirect count", "last redirect", "analytics", "click count"),
            Capability.IDEMPOTENCY, List.of("idempotency", "idempotent", "duplicate request", "duplicate requests"),
            Capability.EXPIRATION, List.of("expire", "expires", "expired", "expiration", "expiry")));

    /** Capabilities implemented in the codebase today. */
    private static final Set<Capability> IMPLEMENTED = EnumSet.of(Capability.CREATE_LINK, Capability.REDIRECT,
            Capability.ANALYTICS, Capability.IDEMPOTENCY, Capability.EXPIRATION);

    private static final String LINK = "com.agentic.shortener.link.";
    private static final String LINK_TESTS = "src/test/java/com/agentic/shortener/link/";

    private final List<CapabilityEntry> entries;

    /** The registry of the real codebase (the application bean). */
    public CapabilityRegistry() {
        this(IMPLEMENTED);
    }

    private CapabilityRegistry(Set<Capability> implemented) {
        this.entries = defaults().stream()
                .map(e -> e.withStatus(implemented.contains(e.capability()) ? CapabilityStatus.IMPLEMENTED
                        : CapabilityStatus.PLANNED))
                .toList();
    }

    /**
     * A registry where exactly the given capabilities are IMPLEMENTED and all others PLANNED. Used by tests to
     * reproduce an earlier or later codebase state; the application bean always reflects the real codebase.
     */
    public static CapabilityRegistry withImplemented(Capability... implemented) {
        return new CapabilityRegistry(implemented.length == 0 ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(List.of(implemented)));
    }

    public static List<String> vocabulary(Capability capability) {
        return VOCABULARY.get(capability);
    }

    public List<CapabilityEntry> entries() {
        return entries;
    }

    public CapabilityEntry entry(Capability capability) {
        return entries.stream().filter(e -> e.capability() == capability).findFirst().orElseThrow();
    }

    private static List<CapabilityEntry> defaults() {
        return List.of(
                define(Capability.CREATE_LINK,
                        List.of("FR-URL-001", "FR-URL-002", "FR-URL-003", "FR-URL-004", "FR-URL-005", "FR-URL-013",
                                "FR-URL-016"),
                        List.of(
                                s("B1", "a client can create a short link for an absolute http/https address with a non-empty host"),
                                s("B2", "other schemes (incl. javascript, file, data) are refused"),
                                s("B3", "localhost and literal loopback/private hosts are refused without name resolution"),
                                s("B4", "each code is unique, URL-safe, 7 characters, regenerated at most 5 times on collision"),
                                s("B5", "addresses up to 2,048 characters"),
                                s("B6", "storage unavailable means creation fails with service-unavailable and no code")),
                        List.of("probe.create-link", "probe.reject-unsafe-url"),
                        List.of("LinkController", "LinkService", "UrlValidator", "ShortCodeGenerator", "Link",
                                "LinkRepository"),
                        List.of("POST /api/links"),
                        List.of("link table (V2__links.sql)"),
                        List.of("UrlValidatorTest", "ShortCodeGeneratorTest", "LinkServiceCollisionTest", "LinkApiTest"),
                        classes("LinkController", "LinkService", "UrlValidator", "ShortCodeGenerator",
                                "SecureRandomShortCodeGenerator", "Link", "LinkRepository"),
                        tests("UrlValidatorTest", "ShortCodeGeneratorTest", "LinkServiceCollisionTest", "LinkApiTest",
                                "LinkStorageFailureTest")),
                define(Capability.REDIRECT,
                        List.of("FR-URL-006", "FR-URL-007", "FR-URL-013", "FR-URL-017"),
                        List.of(
                                s("B1", "an active link redirects to its original address"),
                                s("B2", "an unknown code returns not-found"),
                                s("B3", "storage unavailable means a redirect returns service-unavailable"),
                                s("B4", "an analytics-recording failure does not prevent the redirect")),
                        List.of("probe.redirect", "probe.unknown-code"),
                        List.of("RedirectController", "LinkService", "LinkRepository"),
                        List.of("GET /r/{code}"),
                        List.of("link table (read)"),
                        List.of("LinkApiTest", "LinkStorageFailureTest"),
                        classes("RedirectController", "LinkService", "LinkRepository"),
                        tests("LinkApiTest", "LinkStorageFailureTest")),
                define(Capability.ANALYTICS,
                        List.of("FR-URL-010", "FR-URL-012"),
                        List.of(
                                s("B1", "each successful redirect increments the link's count and sets its last-redirect time, readable by clients"),
                                s("B2", "not-found and expired attempts are not counted"),
                                s("B3", "no count is lost under concurrent redirects")),
                        List.of("probe.redirect-count"),
                        List.of("LinkService", "LinkRepository", "LinkController"),
                        List.of("GET /api/links/{code}"),
                        List.of("link.redirect_count, link.last_redirect_at"),
                        List.of("LinkApiTest", "LinkConcurrencyTest"),
                        classes("LinkService", "LinkRepository", "LinkController"),
                        tests("LinkApiTest", "LinkConcurrencyTest")),
                define(Capability.IDEMPOTENCY,
                        List.of("FR-URL-011"),
                        List.of(
                                s("B1", "a create request may carry an idempotency key"),
                                s("B2", "same key and same content returns the first result"),
                                s("B3", "same key and different content is a conflict"),
                                s("B4", "no key means a new link")),
                        List.of("probe.idempotent-replay", "probe.idempotent-conflict"),
                        List.of("LinkService", "IdempotencyRecord", "IdempotencyRepository", "LinkController"),
                        List.of("POST /api/links (Idempotency-Key header)"),
                        List.of("idempotency_record table"),
                        List.of("LinkApiTest"),
                        classes("LinkService", "IdempotencyRecord", "IdempotencyRepository", "LinkController"),
                        tests("LinkApiTest")),
                define(Capability.EXPIRATION,
                        List.of("FR-URL-008", "FR-URL-009"),
                        List.of(
                                s("B1", "a client may optionally supply an absolute expiration time per link"),
                                s("B2", "it must be in the future"),
                                s("B3", "after it, the link returns an expired result distinct from not-found, with no redirect and no count"),
                                s("B4", "a link without an expiration never expires (existing links are unaffected)")),
                        List.of("probe.expired-link"),
                        List.of("Link", "LinkService", "LinkController", "RedirectController"),
                        List.of("POST /api/links (expiresAt)", "GET /r/{code} (410 Gone)"),
                        List.of("link.expires_at (V3__link_expiration.sql)"),
                        List.of("LinkExpirationTest"),
                        classes("Link", "LinkService", "LinkController", "RedirectController", "LinkConfiguration"),
                        tests("LinkExpirationTest")));
    }

    /** Status is applied by the constructor; component classes and test files are empty until implemented. */
    private static CapabilityEntry define(Capability capability, List<String> requirementIds,
            List<BehaviorStatement> statements, List<String> probeIds, List<String> designComponents,
            List<String> interfaces, List<String> dataChanges, List<String> plannedTests, List<String> componentClasses,
            List<String> testFiles) {
        return new CapabilityEntry(capability, CapabilityStatus.PLANNED, VOCABULARY.get(capability), requirementIds,
                statements, probeIds, designComponents, interfaces, dataChanges, plannedTests, componentClasses,
                testFiles);
    }

    private static List<String> classes(String... simpleNames) {
        return java.util.Arrays.stream(simpleNames).map(n -> LINK + n).toList();
    }

    private static List<String> tests(String... simpleNames) {
        return java.util.Arrays.stream(simpleNames).map(n -> LINK_TESTS + n + ".java").toList();
    }

    private static BehaviorStatement s(String code, String text) {
        return new BehaviorStatement(code, text);
    }
}
