package com.agentic.shortener.workflow.engine;

import java.util.Map;
import java.util.UUID;

/**
 * Everything an executor may read: the run, persisted upstream outputs, its cancellation token (H3) and the
 * attempt's fault point (demonstration fault injection, FR-REL-011; {@link FaultPoint#NONE} normally).
 */
public record StageContext(UUID runId, String requirement, int planVersion,
        Map<Node, Map<String, Object>> upstreamOutputs, CancellationToken cancellation, FaultPoint faultPoint) {

    public StageContext(UUID runId, String requirement, int planVersion, Map<Node, Map<String, Object>> upstreamOutputs,
            CancellationToken cancellation) {
        this(runId, requirement, planVersion, upstreamOutputs, cancellation, FaultPoint.NONE);
    }
}
