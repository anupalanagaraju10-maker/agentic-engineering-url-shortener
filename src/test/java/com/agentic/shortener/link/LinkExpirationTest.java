package com.agentic.shortener.link;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T111 (FR-URL-001, 008, 009, 011): optional absolute expiration per link. A future {@code expiresAt} is
 * accepted and echoed; a time not in the future is refused; after expiry a redirect returns 410 EXPIRED,
 * distinct from 404, without counting; a link without expiration never expires; the idempotency
 * fingerprint includes {@code expiresAt}. Time is controlled through an injected {@link Clock}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LinkExpirationTest.ControlledClock.class)
class LinkExpirationTest {

    @TestConfiguration
    static class ControlledClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        }
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;

    @Test
    void aFutureExpirationIsAcceptedAndEchoed() throws Exception {
        Instant expiresAt = clock.instant().plus(Duration.ofDays(1));
        create("https://example.com/soon", expiresAt.toString(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
        create("https://example.com/forever", null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @Test
    void anExpirationThatIsNotInTheFutureIsRefused() throws Exception {
        for (Instant notFuture : new Instant[] { clock.instant(), clock.instant().minusSeconds(1) }) {
            create("https://example.com/past", notFuture.toString(), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.category").value("VALIDATION"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("future")));
        }
        create("https://example.com/garbled", "tomorrow", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void anExpiredLinkReturns410WithoutRedirectOrCount() throws Exception {
        String code = code(create("https://example.com/limited", clock.instant().plusSeconds(60).toString(), null));
        mvc.perform(get("/r/" + code)).andExpect(status().isFound());

        clock.advance(Duration.ofSeconds(61));

        mvc.perform(get("/r/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.category").value("EXPIRED"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectCount").value(1)); // the expired attempt was not counted
        mvc.perform(get("/r/never-issued"))
                .andExpect(status().isNotFound())                  // expired is distinct from not-found
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void aLinkWithoutExpirationNeverExpires() throws Exception {
        String code = code(create("https://example.com/permanent", null, null));
        clock.advance(Duration.ofDays(365 * 100));
        mvc.perform(get("/r/" + code)).andExpect(status().isFound());
    }

    @Test
    void theIdempotencyFingerprintIncludesTheExpiration() throws Exception {
        String key = "exp-" + UUID.randomUUID();
        String expiresAt = clock.instant().plus(Duration.ofDays(2)).toString();
        String first = code(create("https://example.com/idem-exp", expiresAt, key).andExpect(status().isCreated()));
        create("https://example.com/idem-exp", expiresAt, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(first));
        create("https://example.com/idem-exp", clock.instant().plus(Duration.ofDays(3)).toString(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("CONFLICT"));
        create("https://example.com/idem-exp", null, key).andExpect(status().isConflict());

        String plainKey = "plain-" + UUID.randomUUID(); // without expiresAt the fingerprint is unchanged
        String plain = code(create("https://example.com/idem-plain", null, plainKey));
        create("https://example.com/idem-plain", null, plainKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(plain));
    }

    private ResultActions create(String url, String expiresAt, String key) throws Exception {
        String body = expiresAt == null ? "{\"url\":\"" + url + "\"}"
                : "{\"url\":\"" + url + "\",\"expiresAt\":\"" + expiresAt + "\"}";
        var request = post("/api/links").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String code(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.code");
    }
}
