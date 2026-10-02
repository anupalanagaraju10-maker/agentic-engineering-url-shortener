package com.agentic.shortener.workflow.engine;

import java.util.Map;

/** Outcome of one stage attempt: a success with output and provenance, or a classified failure. */
public record StageResult(boolean success, Map<String, Object> output, Provenance provenance,
        FailureClass failureClass, String failureCode, String failureReason) {

    public static StageResult success(Map<String, Object> output, Provenance provenance) {
        return new StageResult(true, output, provenance, null, null, null);
    }

    public static StageResult failure(FailureClass failureClass, String code, String reason) {
        return new StageResult(false, null, null, failureClass, code, reason);
    }
}
