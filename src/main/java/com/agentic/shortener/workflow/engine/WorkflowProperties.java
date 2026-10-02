package com.agentic.shortener.workflow.engine;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reliability settings (ADR-0005, PVT-001/002): stage timeout, bounded retry with backoff, and the fault
 * injection switch, which is off unless the demo profile or test configuration enables it (CHK038).
 */
@ConfigurationProperties("workflow")
public record WorkflowProperties(@DefaultValue("5s") Duration stageTimeout, @DefaultValue Retry retry,
        @DefaultValue FaultInjection faultInjection) {

    public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue({ "100ms", "200ms" }) List<Duration> backoff) {

        /** Backoff before the given attempt number (2 ⇒ first entry); the last entry repeats if needed. */
        public Duration before(int nextAttempt) {
            return backoff.isEmpty() ? Duration.ZERO : backoff.get(Math.min(nextAttempt - 2, backoff.size() - 1));
        }
    }

    public record FaultInjection(@DefaultValue("false") boolean enabled) {
    }

    /** Defaults used by engine tests that construct the engine directly. */
    public static WorkflowProperties defaults() {
        return new WorkflowProperties(Duration.ofSeconds(5),
                new Retry(3, List.of(Duration.ofMillis(100), Duration.ofMillis(200))), new FaultInjection(false));
    }
}
