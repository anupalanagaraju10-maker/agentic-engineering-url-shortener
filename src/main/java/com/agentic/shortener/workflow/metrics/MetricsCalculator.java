package com.agentic.shortener.workflow.metrics;

import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Demonstration reliability metrics as a pure function of runs and their audit events (FR-OBS-004/005,
 * ADR-0005 §11). Nothing is tracked alongside the events, so every figure can be re-derived from the audit
 * trail (SC-010).
 * <ul>
 *   <li>success rate = COMPLETED / (COMPLETED + FAILED + non-recoverable SAFE_STOPPED); in-progress runs are
 *       excluded (CHK017);</li>
 *   <li>retry frequency = RETRY_SCHEDULED / automated attempts (STAGE_STARTED);</li>
 *   <li>MTTR = Σ recovered incident durations / recovered incidents; open incidents are excluded (CHK030);</li>
 *   <li>automated active time = wall clock minus every waiting interval, where a wait starts when the engine
 *       requests a human decision or the external action and ends at the next HUMAN/AGENT event (CHK016).</li>
 * </ul>
 */
public final class MetricsCalculator {

    public static final String LABEL = "DEMONSTRATION — local runs, not production statistics";

    private static final Set<RunStatus> WAITING = EnumSet.of(RunStatus.RUNNING, RunStatus.AWAITING_CLARIFICATION,
            RunStatus.AWAITING_APPROVAL, RunStatus.AWAITING_IMPLEMENTATION, RunStatus.AWAITING_REWORK);
    private static final Map<AuditEventType, String> WAIT_STARTS = Map.of(
            AuditEventType.APPROVAL_REQUESTED, "AWAITING_APPROVAL",
            AuditEventType.EXCEPTION_REQUESTED, "AWAITING_APPROVAL",
            AuditEventType.CLARIFICATION_REQUESTED, "AWAITING_CLARIFICATION",
            AuditEventType.IMPLEMENTATION_REQUESTED, "AWAITING_IMPLEMENTATION",
            AuditEventType.APPROVAL_REJECTED, "AWAITING_REWORK");

    private MetricsCalculator() {
    }

    public enum Filter {
        ALL, INJECTED_ONLY, NOT_INJECTED
    }

    public record EventFact(int seq, AuditEventType type, ActorType actorType, Instant createdAt,
            Map<String, Object> payload) {
    }

    public record RunFacts(UUID runId, RunStatus status, boolean recoverable, boolean injected, Instant createdAt,
            Instant endedAt, List<EventFact> events) {
    }

    public static Map<String, Object> compute(List<RunFacts> all, Filter filter) {
        List<RunFacts> runs = all.stream().filter(r -> switch (filter) {
            case ALL -> true;
            case INJECTED_ONLY -> r.injected();
            case NOT_INJECTED -> !r.injected();
        }).toList();

        int completed = count(runs, r -> r.status() == RunStatus.COMPLETED);
        int failed = count(runs, r -> r.status() == RunStatus.FAILED);
        int stoppedFinal = count(runs, r -> r.status() == RunStatus.SAFE_STOPPED && !r.recoverable());
        int finished = completed + failed + stoppedFinal;
        int inProgress = count(runs, r -> WAITING.contains(r.status())
                || (r.status() == RunStatus.SAFE_STOPPED && r.recoverable()));

        Map<String, Object> runSection = new LinkedHashMap<>();
        runSection.put("total", runs.size());
        runSection.put("completed", completed);
        runSection.put("failed", failed);
        runSection.put("safeStopped", count(runs, r -> r.status() == RunStatus.SAFE_STOPPED));
        runSection.put("successRate", finished == 0 ? null : (double) completed / finished);
        runSection.put("failureRate", finished == 0 ? null : (double) (failed + stoppedFinal) / finished);

        int retries = events(runs, AuditEventType.RETRY_SCHEDULED);
        int attempts = events(runs, AuditEventType.STAGE_STARTED);
        Map<String, Object> retrySection = new LinkedHashMap<>();
        retrySection.put("count", retries);
        retrySection.put("perStageExecution", attempts == 0 ? null : (double) retries / attempts);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("label", LABEL);
        metrics.put("filter", filter.name());
        metrics.put("injectedRuns", count(all, RunFacts::injected));
        metrics.put("runs", runSection);
        metrics.put("runsInProgress", inProgress);
        metrics.put("retries", retrySection);
        metrics.put("rollbacks", events(runs, AuditEventType.ATTEMPT_ROLLED_BACK));
        metrics.put("compensations", events(runs, AuditEventType.COMPENSATION_COMPLETED));
        metrics.put("recovery", recovery(runs));
        metrics.put("endToEndMs", endToEnd(runs));
        return metrics;
    }

    private static Map<String, Object> recovery(List<RunFacts> runs) {
        List<Long> durations = new ArrayList<>();
        Map<String, List<Long>> byMechanism = new TreeMap<>();
        int recovered = 0;
        int unrecovered = 0;
        int open = 0;
        for (RunFacts run : runs) {
            Set<Integer> opened = new HashSet<>();
            Set<Integer> closed = new HashSet<>();
            for (EventFact e : run.events()) {
                switch (e.type()) {
                    case FAILURE_DETECTED -> opened.add(e.seq());
                    case RECOVERY_COMPLETED -> {
                        recovered++;
                        closed.add(number(e.payload().get("incidentId")).intValue());
                        long ms = number(e.payload().get("durationMs")).longValue();
                        durations.add(ms);
                        byMechanism.computeIfAbsent(String.valueOf(e.payload().get("mechanism")), k -> new ArrayList<>())
                                .add(ms);
                    }
                    case RECOVERY_FAILED -> {
                        unrecovered++;
                        closed.add(number(e.payload().get("incidentId")).intValue());
                    }
                    default -> {
                    }
                }
            }
            opened.removeAll(closed);
            open += opened.size();
        }
        Map<String, Object> mttrByMechanism = new TreeMap<>();
        byMechanism.forEach((mechanism, values) -> mttrByMechanism.put(mechanism, average(values)));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("recoveredIncidents", recovered);
        section.put("unrecoveredIncidents", unrecovered);
        section.put("openIncidents", open);
        section.put("durationsMs", durations);
        section.put("mttrMs", durations.isEmpty() ? null : average(durations));
        section.put("mttrMsByMechanism", mttrByMechanism);
        return section;
    }

    /** Averages over finished runs only: wall clock, automated-active time and waiting time per state. */
    private static Map<String, Object> endToEnd(List<RunFacts> runs) {
        List<Long> wall = new ArrayList<>();
        List<Long> active = new ArrayList<>();
        Map<String, List<Long>> waitingByState = new TreeMap<>();
        for (RunFacts run : runs) {
            if (run.endedAt() == null) {
                continue;
            }
            long wallMs = Duration.between(run.createdAt(), run.endedAt()).toMillis();
            Map<String, Long> waits = waits(run);
            long waited = waits.values().stream().mapToLong(Long::longValue).sum();
            wall.add(wallMs);
            active.add(Math.max(0, wallMs - waited));
            waits.forEach((state, ms) -> waitingByState.computeIfAbsent(state, k -> new ArrayList<>()).add(ms));
        }
        Map<String, Object> waitingAvg = new TreeMap<>();
        waitingByState.forEach((state, values) -> waitingAvg.put(state, average(values)));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("wallClockAvg", wall.isEmpty() ? null : average(wall));
        section.put("automatedActiveAvg", active.isEmpty() ? null : average(active));
        section.put("waitingByStateAvg", waitingAvg);
        return section;
    }

    /** Waiting intervals of one run: from a wait-start event to the next HUMAN or AGENT event (or run end). */
    private static Map<String, Long> waits(RunFacts run) {
        Map<String, Long> waits = new LinkedHashMap<>();
        String state = null;
        Instant since = null;
        for (EventFact e : run.events()) {
            if (state != null && e.actorType() != ActorType.SYSTEM) {
                waits.merge(state, Duration.between(since, e.createdAt()).toMillis(), Long::sum);
                state = null;
            }
            String starts = WAIT_STARTS.get(e.type());
            if (e.type() == AuditEventType.SAFE_STOPPED && Boolean.TRUE.equals(e.payload().get("recoverable"))) {
                starts = "SAFE_STOPPED_RECOVERABLE";
            }
            if (starts != null && state == null) {
                state = starts;
                since = e.createdAt();
            }
        }
        if (state != null && run.endedAt() != null) {
            waits.merge(state, Duration.between(since, run.endedAt()).toMillis(), Long::sum);
        }
        return waits;
    }

    private static int count(List<RunFacts> runs, java.util.function.Predicate<RunFacts> predicate) {
        return (int) runs.stream().filter(predicate).count();
    }

    private static int events(List<RunFacts> runs, AuditEventType type) {
        return (int) runs.stream().flatMap(r -> r.events().stream()).filter(e -> e.type() == type).count();
    }

    private static double average(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }
}
