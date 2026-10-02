package com.agentic.shortener.workflow.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single point in one attempt where its planned fault fires: after the executor's main work, before its
 * completion commit. Executors with their own finalization (TEST probe cleanup) call {@link #reached()}
 * before it; for every other executor the engine calls it after {@code execute} returns. Fires at most once.
 */
public final class FaultPoint {

    public static final FaultPoint NONE = new FaultPoint(null, 0);

    private final Fault fault;
    private final long timeoutSleepMs;
    private final AtomicBoolean fired = new AtomicBoolean();

    FaultPoint(Fault fault, long timeoutSleepMs) {
        this.fault = fault;
        this.timeoutSleepMs = timeoutSleepMs;
    }

    public void reached() {
        if (fault == null || !fired.compareAndSet(false, true)) {
            return;
        }
        switch (fault.type()) {
            case TRANSIENT -> throw new InjectedFault(FailureClass.TRANSIENT, "INJECTED_TRANSIENT");
            case PERMANENT -> throw new InjectedFault(FailureClass.PERMANENT, "INJECTED_PERMANENT");
            case COMPENSATION_FAILURE -> throw new InjectedFault(FailureClass.TRANSIENT, "INJECTED_COMPENSATION_FAILURE");
            case DELAY -> sleep(fault.delayMs());
            case TIMEOUT -> {
                sleep(timeoutSleepMs);
                throw new InjectedFault(FailureClass.TRANSIENT, "INJECTED_TIMEOUT");
            }
        }
    }

    /** True once the planned fault has fired in this attempt (its effects are labelled injected). */
    public boolean fired() {
        return fired.get();
    }

    public Fault fault() {
        return fault;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InjectedFault(FailureClass.TRANSIENT, "INTERRUPTED");
        }
    }
}
