package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (ADR-0005 §5, CHK033). A run found RUNNING was interrupted by a process stop: any stage
 * left RUNNING is rolled back to PENDING, the idempotent compensation sweep runs, and the run becomes a
 * recoverable SAFE_STOPPED with reason INTERRUPTED. Waiting runs are untouched, and nothing is re-executed
 * until a HUMAN resumes the run.
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final RunLocks locks;

    public StartupRecovery(WorkflowStore store, WorkflowEngine engine, RunLocks locks) {
        this.store = store;
        this.engine = engine;
        this.locks = locks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (UUID runId : store.runIdsWithStatus(RunStatus.RUNNING)) {
            try {
                locks.withLock(runId, () -> {
                    recover(runId);
                    return null;
                });
            } catch (ApiException busy) {
                log.info("Run {} is being advanced by a live command; not an interrupted run", runId);
            }
        }
    }

    private void recover(UUID runId) {
        if (store.loadRun(runId).getStatus() != RunStatus.RUNNING) {
            return;
        }
        List<Node> running = store.loadStages(runId).stream().filter(s -> s.getStatus() == StageStatus.RUNNING)
                .map(WorkflowStage::getNode).toList();
        running.forEach(node -> store.rollbackInterrupted(runId, node));
        store.failureDetected(runId, running.isEmpty() ? null : running.get(0), FailureClass.TRANSIENT, "INTERRUPTED",
                false);
        if (!engine.sweepProbeLinks(runId, "startup recovery")) {
            store.safeStop(runId, "INTERRUPTED: compensation failed during startup recovery", false);
            store.recoveryFailedForOpenIncidents(runId, "SAFE_STOPPED");
            return;
        }
        store.safeStop(runId, "INTERRUPTED: the process stopped while the run was RUNNING"
                + (running.isEmpty() ? "" : " (stage " + running + " rolled back)"), true);
        log.warn("Run {} was interrupted by a restart and is now SAFE_STOPPED (recoverable)", runId);
    }
}
