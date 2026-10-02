package com.agentic.shortener.workflow.rules;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Static registry of the URL-shortener capabilities, exactly as research R5 (vocabulary) and R6
 * (requirement IDs and recorded behavior statements). An entry moves from PLANNED to IMPLEMENTED in the
 * same change that implements it.
 */
@Component
public class CapabilityRegistry {

    private final List<CapabilityEntry> entries = List.of(
            planned(Capability.CREATE_LINK,
                    List.of("short link", "short links", "short url", "shorten", "create a link", "create link"),
                    List.of("FR-URL-001", "FR-URL-002", "FR-URL-003", "FR-URL-004", "FR-URL-005", "FR-URL-013",
                            "FR-URL-016"),
                    List.of(
                            s("B1", "a client can create a short link for an absolute http/https address with a non-empty host"),
                            s("B2", "other schemes (incl. javascript, file, data) are refused"),
                            s("B3", "localhost and literal loopback/private hosts are refused without name resolution"),
                            s("B4", "each code is unique, URL-safe, 7 characters, regenerated at most 5 times on collision"),
                            s("B5", "addresses up to 2,048 characters"),
                            s("B6", "storage unavailable means creation fails with service-unavailable and no code")),
                    List.of("probe.create-link", "probe.reject-unsafe-url")),
            planned(Capability.REDIRECT,
                    List.of("redirect", "redirects", "redirected"),
                    List.of("FR-URL-006", "FR-URL-007", "FR-URL-013", "FR-URL-017"),
                    List.of(
                            s("B1", "an active link redirects to its original address"),
                            s("B2", "an unknown code returns not-found"),
                            s("B3", "storage unavailable means a redirect returns service-unavailable"),
                            s("B4", "an analytics-recording failure does not prevent the redirect")),
                    List.of("probe.redirect", "probe.unknown-code")),
            planned(Capability.ANALYTICS,
                    List.of("redirect count", "last redirect", "analytics", "click count"),
                    List.of("FR-URL-010", "FR-URL-012"),
                    List.of(
                            s("B1", "each successful redirect increments the link's count and sets its last-redirect time, readable by clients"),
                            s("B2", "not-found and expired attempts are not counted"),
                            s("B3", "no count is lost under concurrent redirects")),
                    List.of("probe.redirect-count")),
            planned(Capability.IDEMPOTENCY,
                    List.of("idempotency", "idempotent", "duplicate request", "duplicate requests"),
                    List.of("FR-URL-011"),
                    List.of(
                            s("B1", "a create request may carry an idempotency key"),
                            s("B2", "same key and same content returns the first result"),
                            s("B3", "same key and different content is a conflict"),
                            s("B4", "no key means a new link")),
                    List.of("probe.idempotent-replay", "probe.idempotent-conflict")),
            planned(Capability.EXPIRATION,
                    List.of("expire", "expires", "expired", "expiration", "expiry"),
                    List.of("FR-URL-008", "FR-URL-009"),
                    List.of(
                            s("B1", "a client may optionally supply an absolute expiration time per link"),
                            s("B2", "it must be in the future"),
                            s("B3", "after it, the link returns an expired result distinct from not-found, with no redirect and no count"),
                            s("B4", "a link without an expiration never expires (existing links are unaffected)")),
                    List.of("probe.expired-link")));

    public List<CapabilityEntry> entries() {
        return entries;
    }

    public CapabilityEntry entry(Capability capability) {
        return entries.stream().filter(e -> e.capability() == capability).findFirst().orElseThrow();
    }

    private static CapabilityEntry planned(Capability capability, List<String> vocabulary, List<String> requirementIds,
            List<BehaviorStatement> statements, List<String> probeIds) {
        return new CapabilityEntry(capability, CapabilityStatus.PLANNED, vocabulary, requirementIds, statements,
                probeIds, List.of(), List.of());
    }

    private static BehaviorStatement s(String code, String text) {
        return new BehaviorStatement(code, text);
    }
}
