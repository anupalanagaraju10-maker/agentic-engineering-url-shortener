package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * T051: collision handling with a stub generator (FR-URL-004/005, PVT-006): a collision regenerates; five
 * colliding attempts end in CODE_SPACE_EXHAUSTED and no link is created. Also covers the probe-link
 * helpers used later by the TEST stage (T060).
 */
@SpringBootTest
@ActiveProfiles("test")
class LinkServiceCollisionTest {

    @MockitoBean
    private ShortCodeGenerator generator;

    @Autowired
    private LinkService service;

    @Autowired
    private LinkRepository links;

    @Autowired
    private IdempotencyRepository keys;

    @Test
    void aCollisionRegeneratesTheCode() {
        String taken = uniqueCode();
        String fresh = uniqueCode();
        when(generator.next()).thenReturn(taken);
        service.create("https://example.com/first", null);

        when(generator.next()).thenReturn(taken, fresh);
        LinkService.CreateResult result = service.create("https://example.com/second", null);

        assertThat(result.link().getCode()).isEqualTo(fresh);
        assertThat(result.replayed()).isFalse();
        assertThat(links.findByCode(fresh)).get().extracting(Link::getOriginalUrl).isEqualTo("https://example.com/second");
    }

    @Test
    void fiveCollisionsExhaustTheCodeSpaceAndCreateNoLink() {
        String taken = uniqueCode();
        when(generator.next()).thenReturn(taken);
        service.create("https://example.com/occupied", null);
        long before = links.count();

        when(generator.next()).thenReturn(taken);
        assertThatThrownBy(() -> service.create("https://example.com/never", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCategory()).isEqualTo(ErrorCategory.CODE_SPACE_EXHAUSTED);
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        assertThat(links.count()).isEqualTo(before);
        verify(generator, times(1 + LinkService.MAX_CODE_ATTEMPTS)).next();
        assertThat(LinkService.MAX_CODE_ATTEMPTS).isEqualTo(5);
    }

    @Test
    void probeLinksAreTaggedAndDeletedOnlyForTheirRun() {
        when(generator.next()).thenReturn(uniqueCode(), uniqueCode(), uniqueCode());
        UUID run = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        Link probe = service.createProbeLink("https://example.com/probe", run);
        Link otherProbe = service.createProbeLink("https://example.com/probe", otherRun);
        Link client = service.create("https://example.com/client", null).link();

        assertThat(probe.getProbeRunId()).isEqualTo(run);
        assertThat(client.getProbeRunId()).isNull();
        assertThat(service.deleteProbeLinks(run)).isEqualTo(1);
        assertThat(links.findByCode(probe.getCode())).isEmpty();
        assertThat(links.findByCode(otherProbe.getCode())).isPresent();
        assertThat(links.findByCode(client.getCode())).isPresent();
    }

    @Test
    void idempotentProbeLinksAreCountedAndFullyRemovedWithTheirKeys() {
        when(generator.next()).thenReturn(uniqueCode(), uniqueCode());
        UUID run = UUID.randomUUID();
        String key = "probe-" + run;
        LinkService.CreateResult first = service.createProbeLink("https://example.com/idem-probe", run, key);
        LinkService.CreateResult replay = service.createProbeLink("https://example.com/idem-probe", run, key);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().getCode()).isEqualTo(first.link().getCode());
        assertThat(service.countProbeLinks(run)).isEqualTo(1);
        assertThat(service.deleteProbeLinks(run)).isEqualTo(1); // the key row is removed with its link
        assertThat(service.countProbeLinks(run)).isZero();
        assertThat(keys.findById(key)).isEmpty();
    }

    private static String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }
}
