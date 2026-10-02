package com.agentic.shortener.workflow.engine;

import java.util.Map;
import java.util.UUID;

/** Everything an executor may read: the run, persisted upstream outputs, and its cancellation token. */
public record StageContext(UUID runId, String requirement, int planVersion,
        Map<Node, Map<String, Object>> upstreamOutputs, CancellationToken cancellation) {
}
