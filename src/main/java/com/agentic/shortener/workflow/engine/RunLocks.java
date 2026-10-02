package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * One in-JVM lock per run (single process, ADR-0003). Commands never wait: a busy run is rejected with
 * 409 (H4). Store writes assert the lock is held by the current thread (H1).
 */
@Component
public class RunLocks {

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(UUID runId, Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(runId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ApiException(ErrorCategory.CONFLICT, HttpStatus.CONFLICT,
                    "run busy: another command is currently advancing run " + runId);
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public void assertHeld(UUID runId) {
        ReentrantLock lock = locks.get(runId);
        if (lock == null || !lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("workflow writes for run " + runId
                    + " must happen on the coordinating thread holding the run lock");
        }
    }
}
