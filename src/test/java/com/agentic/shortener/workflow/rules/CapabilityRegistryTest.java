package com.agentic.shortener.workflow.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** T017: capability registry content exactly as research R5/R6 (status, vocabulary, IDs, statements). */
class CapabilityRegistryTest {

    private final CapabilityRegistry registry = new CapabilityRegistry();

    @Test
    void listsTheFiveCapabilitiesInOrder() {
        assertThat(registry.entries()).extracting(CapabilityEntry::capability).containsExactly(
                Capability.CREATE_LINK, Capability.REDIRECT, Capability.ANALYTICS, Capability.IDEMPOTENCY,
                Capability.EXPIRATION);
    }

    @Test
    void coreCapabilitiesAreImplementedAndExpirationIsStillPlanned() {
        // T062: Phase 4 (SCN-A implementation) implemented the core shortener; expiration waits for SCN-B.
        assertThat(registry.entries()).extracting(CapabilityEntry::capability, CapabilityEntry::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Capability.CREATE_LINK, CapabilityStatus.IMPLEMENTED),
                org.assertj.core.groups.Tuple.tuple(Capability.REDIRECT, CapabilityStatus.IMPLEMENTED),
                org.assertj.core.groups.Tuple.tuple(Capability.ANALYTICS, CapabilityStatus.IMPLEMENTED),
                org.assertj.core.groups.Tuple.tuple(Capability.IDEMPOTENCY, CapabilityStatus.IMPLEMENTED),
                org.assertj.core.groups.Tuple.tuple(Capability.EXPIRATION, CapabilityStatus.PLANNED));
    }

    @Test
    void implementedEntriesCiteMigrationV2AndTheirTests() {
        assertThat(registry.entry(Capability.CREATE_LINK).componentClasses())
                .contains("com.agentic.shortener.link.LinkService", "com.agentic.shortener.link.UrlValidator");
        assertThat(registry.entry(Capability.IDEMPOTENCY).testFiles())
                .contains("src/test/java/com/agentic/shortener/link/LinkApiTest.java");
        assertThat(Files.exists(Path.of("src/main/resources/db/migration/V2__links.sql"))).isTrue();
        assertThat(registry.entry(Capability.EXPIRATION).componentClasses()).isEmpty();
    }

    @Test
    void fixtureRegistriesCanReproduceEarlierCodebaseStates() {
        assertThat(CapabilityRegistry.withImplemented().entries())
                .allSatisfy(e -> assertThat(e.status()).isEqualTo(CapabilityStatus.PLANNED));
        assertThat(CapabilityRegistry.withImplemented(Capability.ANALYTICS).entry(Capability.CREATE_LINK).status())
                .isEqualTo(CapabilityStatus.PLANNED);
    }

    @Test
    void requirementIdsMatchResearchR6() {
        assertThat(ids(Capability.CREATE_LINK)).containsExactly(
                "FR-URL-001", "FR-URL-002", "FR-URL-003", "FR-URL-004", "FR-URL-005", "FR-URL-013", "FR-URL-016");
        assertThat(ids(Capability.REDIRECT)).containsExactly("FR-URL-006", "FR-URL-007", "FR-URL-013", "FR-URL-017");
        assertThat(ids(Capability.ANALYTICS)).containsExactly("FR-URL-010", "FR-URL-012");
        assertThat(ids(Capability.IDEMPOTENCY)).containsExactly("FR-URL-011");
        assertThat(ids(Capability.EXPIRATION)).containsExactly("FR-URL-008", "FR-URL-009");
    }

    @Test
    void recordedBehaviorStatementsMatchResearchR6() {
        assertStatementCodes(Capability.CREATE_LINK, 6);
        assertStatementCodes(Capability.REDIRECT, 4);
        assertStatementCodes(Capability.ANALYTICS, 3);
        assertStatementCodes(Capability.IDEMPOTENCY, 4);
        assertStatementCodes(Capability.EXPIRATION, 4);
        assertThat(statement(Capability.EXPIRATION, "B4")).contains("never expires");
        assertThat(statement(Capability.CREATE_LINK, "B4")).contains("7 characters").contains("5 times");
        assertThat(statement(Capability.IDEMPOTENCY, "B3")).contains("conflict");
    }

    @Test
    void vocabularyMatchesResearchR5() {
        assertThat(registry.entry(Capability.CREATE_LINK).vocabulary()).containsExactly(
                "short link", "short links", "short url", "shorten", "create a link", "create link");
        assertThat(registry.entry(Capability.REDIRECT).vocabulary()).containsExactly("redirect", "redirects", "redirected");
        assertThat(registry.entry(Capability.ANALYTICS).vocabulary()).containsExactly(
                "redirect count", "last redirect", "analytics", "click count");
        assertThat(registry.entry(Capability.IDEMPOTENCY).vocabulary()).containsExactly(
                "idempotency", "idempotent", "duplicate request", "duplicate requests");
        assertThat(registry.entry(Capability.EXPIRATION).vocabulary()).containsExactly(
                "expire", "expires", "expired", "expiration", "expiry");
    }

    @Test
    void everyEntryHasAcceptanceProbes() {
        assertThat(registry.entries()).allSatisfy(e -> assertThat(e.probeIds()).isNotEmpty());
    }

    @Test
    void implementedEntriesReferenceRealClassesAndTests() {
        registry.entries().stream().filter(e -> e.status() == CapabilityStatus.IMPLEMENTED).forEach(e -> {
            assertThat(e.componentClasses()).isNotEmpty();
            assertThat(e.testFiles()).isNotEmpty();
            e.componentClasses().forEach(c -> assertThatCode(() -> Class.forName(c)).doesNotThrowAnyException());
            e.testFiles().forEach(f -> assertThat(Files.exists(Path.of(f))).as(f).isTrue());
        });
    }

    private java.util.List<String> ids(Capability capability) {
        return registry.entry(capability).requirementIds();
    }

    private void assertStatementCodes(Capability capability, int count) {
        assertThat(registry.entry(capability).statements()).extracting(BehaviorStatement::code)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, count).mapToObj(i -> "B" + i).toList());
        assertThat(registry.entry(capability).statements()).allSatisfy(s -> assertThat(s.text()).isNotBlank());
    }

    private String statement(Capability capability, String code) {
        return registry.entry(capability).statements().stream().filter(s -> s.code().equals(code))
                .findFirst().orElseThrow().text();
    }
}
