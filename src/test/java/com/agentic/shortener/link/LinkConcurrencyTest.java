package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T054: 50 concurrent creates and 50 concurrent redirects of one link (FR-URL-012, PVT-008, SC-009). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkConcurrencyTest {

    private static final int CLIENTS = 50;

    @Autowired
    private MockMvc mvc;

    @Test
    void fiftyConcurrentCreatesYieldFiftyDistinctCodes() throws Exception {
        List<String> responses = concurrently(() -> {
            var result = mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/concurrent\"}")).andReturn().getResponse();
            assertThat(result.getStatus()).isEqualTo(201);
            return result.getContentAsString();
        });
        Set<String> codes = new HashSet<>();
        responses.forEach(body -> codes.add(JsonPath.read(body, "$.code")));
        assertThat(codes).hasSize(CLIENTS);
    }

    @Test
    void fiftyConcurrentRedirectsLoseNoCount() throws Exception {
        String body = mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/popular\"}")).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");

        List<String> statuses = concurrently(
                () -> String.valueOf(mvc.perform(get("/r/" + code)).andReturn().getResponse().getStatus()));

        assertThat(statuses).hasSize(CLIENTS).allMatch("302"::equals);
        String analytics = mvc.perform(get("/api/links/" + code)).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(analytics, "$.redirectCount")).longValue()).isEqualTo(CLIENTS);
    }

    private static List<String> concurrently(Callable<String> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
