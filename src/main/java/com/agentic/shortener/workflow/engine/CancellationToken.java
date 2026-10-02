package com.agentic.shortener.workflow.engine;

/** Per-attempt cancellation flag; executors check it before each side effect (H3). */
public final class CancellationToken {

    private volatile boolean revoked;

    public void revoke() {
        revoked = true;
    }

    public boolean isRevoked() {
        return revoked;
    }
}
