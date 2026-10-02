package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.rules.Capability;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Typed access to upstream stage outputs (which are persisted as JSON maps). */
final class Outputs {

    private Outputs() {
    }

    static Map<String, Object> of(StageContext context, Node node) {
        Map<String, Object> output = context.upstreamOutputs().get(node);
        return output == null ? Map.of() : output;
    }

    static List<String> strings(Object value) {
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    static List<Capability> capabilities(StageContext context) {
        return strings(of(context, Node.UNDERSTAND).get("capabilities")).stream().map(Capability::valueOf).toList();
    }

    static String normalized(StageContext context) {
        Object normalized = of(context, Node.UNDERSTAND).get("normalized");
        return normalized == null ? "" : normalized.toString();
    }

    static String changeType(StageContext context) {
        Object changeType = of(context, Node.UNDERSTAND).get("changeType");
        return changeType == null ? "GREENFIELD" : changeType.toString();
    }
}
