package com.agentic.shortener.workflow.engine;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One DAG node: kind, dependencies and, for conditional nodes, the branch condition evaluated over the
 * outputs of already-succeeded nodes.
 */
public record NodeDefinition(Node node, NodeKind kind, boolean conditional, Set<Node> dependsOn,
        Predicate<Map<Node, Map<String, Object>>> condition, String conditionDescription) {
}
