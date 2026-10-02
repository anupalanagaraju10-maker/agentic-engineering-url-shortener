package com.agentic.shortener.workflow.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.RunStatus;
import com.agentic.shortener.workflow.metrics.MetricsCalculator.EventFact;
import com.agentic.shortener.workflow.metrics.MetricsCalculator.Filter;
import com.agentic.shortener.workflow.metrics.MetricsCalculator.RunFacts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T101 (FR-OBS-004/005, CHK016/017/030/038, SC-010): metrics are derived only from runs and audit events. The
 * calculation is checked over a known, hand-built event sequence; the endpoint is checked for shape, label
 * and filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricsTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Test
    void ratesCountsAndMttrFollowTheDefinitions() {
        Map<String, Object> m = MetricsCalculator.compute(knownRuns(), Filter.ALL);

        assertThat(m.get("label")).isEqualTo(MetricsCalculator.LABEL);
        Map<String, Object> runs = map(m.get("runs"));
        assertThat(runs).containsEntry("total", 5).containsEntry("completed", 2).containsEntry("failed", 1)
                .containsEntry("safeStopped", 1);
        // success = COMPLETED / (COMPLETED + FAILED + non-recoverable SAFE_STOPPED) = 2 / 4
        assertThat((Double) runs.get("successRate")).isCloseTo(0.5, within(1e-9));
        assertThat((Double) runs.get("failureRate")).isCloseTo(0.5, within(1e-9));
        assertThat(m.get("runsInProgress")).isEqualTo(1); // waiting runs are excluded from rates

        Map<String, Object> retries = map(m.get("retries"));
        assertThat(retries).containsEntry("count", 2);
        // retries / automated attempts (STAGE_STARTED): 2 / 8
        assertThat((Double) retries.get("perStageExecution")).isCloseTo(0.25, within(1e-9));
        assertThat(m.get("rollbacks")).isEqualTo(2);
        assertThat(m.get("compensations")).isEqualTo(1);

        Map<String, Object> recovery = map(m.get("recovery"));
        assertThat(recovery).containsEntry("recoveredIncidents", 2).containsEntry("unrecoveredIncidents", 1)
                .containsEntry("openIncidents", 1);
        assertThat(recovery.get("durationsMs")).isEqualTo(List.of(300L, 60_000L));
        assertThat((Double) recovery.get("mttrMs")).isCloseTo(30_150.0, within(1e-9)); // (300 + 60000) / 2
        assertThat(map(recovery.get("mttrMsByMechanism"))).containsEntry("RETRY", 300.0).containsEntry("RESUME", 60_000.0);
    }

    @Test
    void automatedActiveTimeExcludesEveryWaitingInterval() {
        Map<String, Object> e2e = map(MetricsCalculator.compute(knownRuns(), Filter.ALL).get("endToEndMs"));

        // run A: 10 min wall clock, of which 9 min waiting (approval 5 + implementation 3 + release 1) ⇒ 1 min active
        // run B: 2 min wall clock, no waiting ⇒ 2 min active; finished runs C and D are counted too
        Map<String, Object> waiting = map(e2e.get("waitingByStateAvg"));
        assertThat(waiting).containsKeys("AWAITING_APPROVAL", "AWAITING_IMPLEMENTATION");
        assertThat((Double) e2e.get("automatedActiveAvg")).isLessThan((Double) e2e.get("wallClockAvg"));
        List<RunFacts> onlyA = knownRuns().subList(0, 1);
        Map<String, Object> a = map(MetricsCalculator.compute(onlyA, Filter.ALL).get("endToEndMs"));
        assertThat((Double) a.get("wallClockAvg")).isCloseTo(600_000.0, within(1e-9));
        assertThat((Double) a.get("automatedActiveAvg")).isCloseTo(60_000.0, within(1e-9));
    }

    @Test
    void injectedRunsAreSeparable() {
        Map<String, Object> notInjected = MetricsCalculator.compute(knownRuns(), Filter.NOT_INJECTED);
        Map<String, Object> injectedOnly = MetricsCalculator.compute(knownRuns(), Filter.INJECTED_ONLY);

        assertThat(map(notInjected.get("runs")).get("total")).isEqualTo(3);
        assertThat(map(injectedOnly.get("runs")).get("total")).isEqualTo(2);
        assertThat(notInjected.get("injectedRuns")).isEqualTo(2); // always reported
        assertThat(notInjected.get("filter")).isEqualTo("NOT_INJECTED");
        assertThat(map(injectedOnly.get("retries")).get("count")).isEqualTo(2);
    }

    @Test
    void theEndpointServesLabelledDemonstrationMetrics() throws Exception {
        mvc.perform(get("/api/metrics/workflows")).andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("DEMONSTRATION — local runs, not production statistics"))
                .andExpect(jsonPath("$.filter").value("ALL"))
                .andExpect(jsonPath("$.runs.total").isNumber())
                .andExpect(jsonPath("$.recovery").exists());
        mvc.perform(get("/api/metrics/workflows").param("faultInjected", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("INJECTED_ONLY"));
        mvc.perform(get("/api/metrics/workflows").param("faultInjected", "false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("NOT_INJECTED"));
    }

    // ------------------------------------------------------------------ known event sequence (fixture)

    /**
     * A: COMPLETED, not injected, waits 5 + 3 + 1 min. B: COMPLETED, injected, one retried failure (300 ms).
     * C: FAILED, injected, unrecovered incident. D: non-recoverable SAFE_STOPPED (policy). E: waiting at
     * design approval with an incident that is closed by a resume after 60 s, then still in progress.
     */
    static List<RunFacts> knownRuns() {
        List<RunFacts> runs = new ArrayList<>();
        Builder a = new Builder(RunStatus.COMPLETED, false, false, T0, T0.plusSeconds(600));
        a.at(0, AuditEventType.RUN_CREATED, ActorType.HUMAN).stage(10).at(20, AuditEventType.APPROVAL_REQUESTED, ActorType.SYSTEM)
                .at(320, AuditEventType.APPROVAL_GRANTED, ActorType.HUMAN)
                .at(330, AuditEventType.IMPLEMENTATION_REQUESTED, ActorType.SYSTEM)
                .at(510, AuditEventType.IMPLEMENTATION_RECORDED, ActorType.AGENT).stage(520)
                .at(530, AuditEventType.APPROVAL_REQUESTED, ActorType.SYSTEM)
                .at(590, AuditEventType.APPROVAL_GRANTED, ActorType.HUMAN).stage(595)
                .at(600, AuditEventType.RUN_COMPLETED, ActorType.SYSTEM);
        runs.add(a.build());

        Builder b = new Builder(RunStatus.COMPLETED, false, true, T0, T0.plusSeconds(120));
        b.at(0, AuditEventType.RUN_CREATED, ActorType.HUMAN).stage(1)
                .incident(2, 1, AuditEventType.FAILURE_DETECTED, null, null)
                .at(2, AuditEventType.ATTEMPT_ROLLED_BACK, ActorType.SYSTEM)
                .incident(2, 2, AuditEventType.RECOVERY_STARTED, 1, "RETRY")
                .at(2, AuditEventType.RETRY_SCHEDULED, ActorType.SYSTEM).stage(3)
                .completed(3, 1, "RETRY", 300).at(120, AuditEventType.RUN_COMPLETED, ActorType.SYSTEM);
        runs.add(b.build());

        Builder c = new Builder(RunStatus.FAILED, false, true, T0, T0.plusSeconds(30));
        c.at(0, AuditEventType.RUN_CREATED, ActorType.HUMAN).stage(1)
                .incident(2, 1, AuditEventType.FAILURE_DETECTED, null, null)
                .at(2, AuditEventType.ATTEMPT_ROLLED_BACK, ActorType.SYSTEM)
                .at(2, AuditEventType.COMPENSATION_COMPLETED, ActorType.SYSTEM)
                .at(2, AuditEventType.RETRY_SCHEDULED, ActorType.SYSTEM).stage(3)
                .incident(30, 1, AuditEventType.RECOVERY_FAILED, 1, null)
                .at(30, AuditEventType.RUN_FAILED, ActorType.SYSTEM);
        runs.add(c.build());

        Builder d = new Builder(RunStatus.SAFE_STOPPED, false, false, T0, T0.plusSeconds(10));
        d.at(0, AuditEventType.RUN_CREATED, ActorType.HUMAN).stage(1).at(10, AuditEventType.SAFE_STOPPED, ActorType.SYSTEM);
        runs.add(d.build());

        Builder e = new Builder(RunStatus.AWAITING_APPROVAL, false, false, T0, null);
        e.at(0, AuditEventType.RUN_CREATED, ActorType.HUMAN)
                .incident(1, 1, AuditEventType.FAILURE_DETECTED, null, null)
                .incident(1, 2, AuditEventType.RECOVERY_STARTED, 1, "RESUME")
                .completed(61, 1, "RESUME", 60_000)
                .incident(62, 9, AuditEventType.FAILURE_DETECTED, null, null) // still open
                .at(63, AuditEventType.APPROVAL_REQUESTED, ActorType.SYSTEM);
        runs.add(e.build());
        return runs;
    }

    static final class Builder {
        final RunStatus status;
        final boolean recoverable;
        final boolean injected;
        final Instant created;
        final Instant ended;
        final List<EventFact> events = new ArrayList<>();

        Builder(RunStatus status, boolean recoverable, boolean injected, Instant created, Instant ended) {
            this.status = status;
            this.recoverable = recoverable;
            this.injected = injected;
            this.created = created;
            this.ended = ended;
        }

        Builder at(long second, AuditEventType type, ActorType actor) {
            events.add(new EventFact(events.size() + 1, type, actor, T0.plusSeconds(second), Map.of()));
            return this;
        }

        Builder stage(long second) {
            return at(second, AuditEventType.STAGE_STARTED, ActorType.SYSTEM).at(second, AuditEventType.STAGE_SUCCEEDED,
                    ActorType.SYSTEM);
        }

        Builder incident(long second, int ignoredSeq, AuditEventType type, Integer incidentId, String mechanism) {
            Map<String, Object> payload = new java.util.HashMap<>();
            if (incidentId != null) {
                payload.put("incidentId", seqOfFirst(incidentId));
            }
            if (mechanism != null) {
                payload.put("mechanism", mechanism);
            }
            events.add(new EventFact(events.size() + 1, type, ActorType.SYSTEM, T0.plusSeconds(second), payload));
            return this;
        }

        Builder completed(long second, int incidentOrdinal, String mechanism, long durationMs) {
            events.add(new EventFact(events.size() + 1, AuditEventType.RECOVERY_COMPLETED, ActorType.SYSTEM,
                    T0.plusSeconds(second), Map.of("incidentId", seqOfFirst(incidentOrdinal), "mechanism", mechanism,
                            "durationMs", durationMs)));
            return this;
        }

        /** The seq of the n-th FAILURE_DETECTED event of this run (the incident id). */
        private int seqOfFirst(int ordinal) {
            return events.stream().filter(e -> e.type() == AuditEventType.FAILURE_DETECTED).skip(ordinal - 1)
                    .findFirst().map(EventFact::seq).orElseThrow();
        }

        RunFacts build() {
            return new RunFacts(UUID.randomUUID(), status, recoverable, injected, created, ended, events);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
