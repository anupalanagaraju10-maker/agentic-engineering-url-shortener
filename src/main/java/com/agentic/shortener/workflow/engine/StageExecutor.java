package com.agentic.shortener.workflow.engine;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic logic for one AUTOMATED node (ADR-0003). Executors run on pool threads and must not
 * write to the database or the audit trail; the engine persists their results (H1).
 */
public interface StageExecutor {

    Node node();

    StageResult execute(StageContext context);

    /** Entry condition beyond dependency completion; a violation means the stage never starts (FR-ORC-008). */
    default Optional<String> checkEntry(StageContext context) {
        return Optional.empty();
    }

    /** Exit condition on the produced output; a violation means the stage is not marked succeeded. */
    default Optional<String> checkExit(Map<String, Object> output) {
        return Optional.empty();
    }

    /** Whether a degraded fallback exists once retries are exhausted (ADR-0005 §4: DOCS only). */
    default boolean supportsFallback() {
        return false;
    }

    /** The fallback result, provenance FALLBACK; called only when {@link #supportsFallback()} is true. */
    default StageResult fallback(StageContext context) {
        throw new UnsupportedOperationException(node() + " has no fallback");
    }
}
