package com.agentic.shortener;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T125 (NFR-010, SC-011): demonstration measurements of PVT-003 (p95 create and redirect), PVT-004 (SCN-A
 * automated-active duration) and PVT-005 (startup to healthy). Tagged {@code measurement}, excluded from the
 * normal build; run with {@code ./mvnw test -Dgroups=measurement -Dtest.excluded.groups=none}. Non-blocking:
 * the results are printed and written to {@code target/measurements.txt}; only sanity is asserted.
 * Requests go through MockMvc in-process, so HTTP network time is not included.
 */
@Tag("measurement")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerformanceMeasurementTest {

    private static final int REQUESTS = 500;
    private static final List<String> RESULTS = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    private MockMvc mvc;

    @Test
    void pvt003CreateAndRedirectLatency() throws Exception {
        for (int i = 0; i < 50; i++) { // warm-up, not measured
            mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/w\"}"));
        }
        List<Long> create = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            long t0 = System.nanoTime();
            String body = mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/m" + i + "\"}")).andReturn().getResponse().getContentAsString();
            create.add(System.nanoTime() - t0);
            codes.add(JsonPath.read(body, "$.code"));
        }
        List<Long> redirect = new ArrayList<>();
        for (String code : codes) {
            long t0 = System.nanoTime();
            int status = mvc.perform(get("/r/" + code)).andReturn().getResponse().getStatus();
            redirect.add(System.nanoTime() - t0);
            assertThat(status).isEqualTo(302);
        }
        record("PVT-003 create   p50=%.2f ms p95=%.2f ms max=%.2f ms (n=%d, target p95 < 1000 ms)", create);
        record("PVT-003 redirect p50=%.2f ms p95=%.2f ms max=%.2f ms (n=%d, target p95 < 1000 ms)", redirect);
    }

    @Test
    void pvt004ScnAAutomatedActiveDuration() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        long active = 0;
        long t0 = System.nanoTime();
        UUID runId = api.createRun(SCN_A);                               // INTAKE..DESIGN, stops at the gate
        active += System.nanoTime() - t0;
        t0 = System.nanoTime();
        api.approve(runId, "DESIGN_APPROVAL", 1);                         // human wait excluded by construction
        active += System.nanoTime() - t0;
        t0 = System.nanoTime();
        api.fixtureEvidence(runId);                                       // TEST ‖ DOCS ‖ SECURITY, readiness
        active += System.nanoTime() - t0;
        t0 = System.nanoTime();
        api.approveRelease(runId, 1, List.of("measurement fixture"));     // FINAL_REPORT
        active += System.nanoTime() - t0;
        assertThat(api.status(runId)).isEqualTo("COMPLETED");
        RESULTS.add(String.format("PVT-004 SCN-A automated-active duration = %.0f ms (sum of the four commands, human "
                + "waits excluded; target < 60000 ms)", active / 1e6));
    }

    @Test
    void pvt005StartupToHealthy() {
        Instant start = Instant.now();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ShortenerApplication.class)
                .profiles("test").run("--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                        "--spring.datasource.url=jdbc:h2:mem:startup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")) {
            Status health = context.getBean(HealthEndpoint.class).health().getStatus();
            long ms = Duration.between(start, Instant.now()).toMillis();
            assertThat(health).isEqualTo(Status.UP);
            RESULTS.add(String.format("PVT-005 startup to healthy = %d ms (fresh context, in-memory database, "
                    + "health %s; target < 30000 ms)", ms, health));
        }
    }

    @AfterAll
    static void write() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("DEMONSTRATION measurements, " + Instant.now() + ", Java " + System.getProperty("java.version") + ", "
                + System.getProperty("os.name") + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
        lines.addAll(RESULTS);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/measurements.txt"), lines);
        lines.forEach(System.out::println);
    }

    private static void record(String format, List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        RESULTS.add(String.format(format, percentile(sorted, 50), percentile(sorted, 95),
                sorted.get(sorted.size() - 1) / 1e6, sorted.size()));
    }

    private static double percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index)) / 1e6;
    }
}
