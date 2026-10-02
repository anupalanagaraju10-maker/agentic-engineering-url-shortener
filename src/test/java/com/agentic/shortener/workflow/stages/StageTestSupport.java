package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.CancellationToken;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Builds stage contexts for executor unit tests (executors are pure: no persistence, H1). */
final class StageTestSupport {

    private StageTestSupport() {
    }

    static StageContext context(String requirement, Map<Node, Map<String, Object>> upstream) {
        return new StageContext(UUID.randomUUID(), requirement, 1, new EnumMap<>(upstream.isEmpty()
                ? new EnumMap<Node, Map<String, Object>>(Node.class) : upstream), new CancellationToken());
    }

    static Map<String, Object> outputOf(StageResult result) {
        if (!result.success()) {
            throw new AssertionError("expected success but was " + result.failureCode() + ": " + result.failureReason());
        }
        return result.output();
    }
}
