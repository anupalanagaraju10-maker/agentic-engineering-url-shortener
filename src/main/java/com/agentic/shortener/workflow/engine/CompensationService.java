package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.link.LinkService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compensation for side effects already committed outside the stage transaction (ADR-0005 §6, FR-REL-006):
 * an idempotent sweep deleting every probe link tagged with the run. Runs on the coordinating thread (H1).
 * The sweep compensating a failed TEST attempt is always recorded; housekeeping sweeps (before a TEST
 * attempt, at run end, on terminate, at startup) are recorded only when they removed something.
 */
@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);

    private final LinkService links;
    private final WorkflowStore store;

    public CompensationService(LinkService links, WorkflowStore store) {
        this.links = links;
        this.store = store;
    }

    /** Compensates a failed TEST attempt. Returns false if the sweep failed, so the caller safe-stops. */
    public boolean compensateFailedAttempt(UUID runId, Integer incidentId, boolean injected, boolean injectFailure) {
        store.compensationStarted(runId, "failed TEST attempt", incidentId, injected);
        if (injectFailure) {
            store.compensationFailed(runId, "injected compensation failure", incidentId, true);
            return false;
        }
        try {
            int deleted = links.deleteProbeLinks(runId);
            store.compensationCompleted(runId, deleted, incidentId, injected);
            return true;
        } catch (RuntimeException e) {
            log.error("Compensation sweep failed for run {}", runId, e);
            store.compensationFailed(runId, e.getClass().getSimpleName(), incidentId, injected);
            return false;
        }
    }

    /** Idempotent housekeeping sweep, recorded only when it removed probe links. False if it failed. */
    public boolean sweep(UUID runId, String trigger) {
        try {
            if (links.countProbeLinks(runId) == 0) {
                return true;
            }
            store.compensationStarted(runId, trigger, null, false);
            int deleted = links.deleteProbeLinks(runId);
            store.compensationCompleted(runId, deleted, null, false);
            return true;
        } catch (RuntimeException e) {
            log.error("Compensation sweep ({}) failed for run {}", trigger, runId, e);
            store.compensationFailed(runId, e.getClass().getSimpleName(), null, false);
            return false;
        }
    }
}
