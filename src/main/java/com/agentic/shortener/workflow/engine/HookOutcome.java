package com.agentic.shortener.workflow.engine;

import java.util.Map;

/** What the engine must do after a post-stage hook ran: continue, safe-stop, or wait for a human. */
public record HookOutcome(Kind kind, String reason, boolean recoverable, String pendingAction,
        Map<String, Object> payload) {

    public enum Kind {
        CONTINUE,
        SAFE_STOP,
        WAIT_FOR_EXCEPTION_DECISION
    }

    public static HookOutcome proceed() {
        return new HookOutcome(Kind.CONTINUE, null, false, null, Map.of());
    }

    public static HookOutcome safeStop(String reason, boolean recoverable) {
        return new HookOutcome(Kind.SAFE_STOP, reason, recoverable, null, Map.of());
    }

    public static HookOutcome waitForException(String checkId, String reason) {
        return new HookOutcome(Kind.WAIT_FOR_EXCEPTION_DECISION, reason, false, "EXCEPTION:" + checkId,
                Map.of("checkId", checkId, "reason", reason));
    }
}
