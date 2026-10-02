package com.agentic.shortener.workflow.engine;

/**
 * One planned fault: it fires on attempts 1..{@code times} of {@code stage} (automated nodes only), after the
 * executor's work and before the completion commit. {@code delayMs} applies to DELAY.
 */
public record Fault(Node stage, FaultType type, int times, long delayMs) {
}
