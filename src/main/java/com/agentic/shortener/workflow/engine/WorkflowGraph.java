package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.engine.Node.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The static, explicit workflow DAG (FR-ORC-001/002, ADR-0003, plan §Workflow DAG). */
public final class WorkflowGraph {

    private final Map<Node, NodeDefinition> definitions = new EnumMap<>(Node.class);

    private WorkflowGraph(List<NodeDefinition> nodes) {
        nodes.forEach(d -> definitions.put(d.node(), d));
    }

    public static WorkflowGraph standard() {
        return new WorkflowGraph(List.of(
                node(INTAKE, NodeKind.AUTOMATED),
                node(UNDERSTAND, NodeKind.AUTOMATED, INTAKE),
                new NodeDefinition(CLARIFICATION, NodeKind.HUMAN_GATE, true, EnumSet.of(UNDERSTAND),
                        outputs -> !findings(outputs.get(UNDERSTAND)).isEmpty(),
                        "UNDERSTAND reported material ambiguity findings"),
                node(DECOMPOSE, NodeKind.AUTOMATED, UNDERSTAND, CLARIFICATION),
                new NodeDefinition(IMPACT_ANALYSIS, NodeKind.AUTOMATED, true, EnumSet.of(DECOMPOSE),
                        outputs -> "BROWNFIELD".equals(value(outputs.get(UNDERSTAND), "changeType")),
                        "UNDERSTAND classified the change as BROWNFIELD"),
                node(DESIGN, NodeKind.AUTOMATED, DECOMPOSE, IMPACT_ANALYSIS),
                node(DESIGN_APPROVAL, NodeKind.HUMAN_GATE, DESIGN),
                node(IMPLEMENT, NodeKind.EXTERNAL_ACTION, DESIGN_APPROVAL),
                node(TEST, NodeKind.AUTOMATED, IMPLEMENT),
                node(DOCS, NodeKind.AUTOMATED, IMPLEMENT),
                node(SECURITY, NodeKind.AUTOMATED, IMPLEMENT),
                node(RELEASE_READINESS, NodeKind.AUTOMATED, TEST, DOCS, SECURITY),
                node(RELEASE_APPROVAL, NodeKind.HUMAN_GATE, RELEASE_READINESS),
                node(FINAL_REPORT, NodeKind.AUTOMATED, RELEASE_APPROVAL)));
    }

    public List<NodeDefinition> nodes() {
        return List.copyOf(definitions.values());
    }

    public NodeDefinition definition(Node node) {
        return definitions.get(node);
    }

    /** Kahn-style ordering; fails if the graph has a cycle. */
    public List<Node> topologicalOrder() {
        List<Node> order = new ArrayList<>();
        Set<Node> done = EnumSet.noneOf(Node.class);
        while (order.size() < definitions.size()) {
            Node next = definitions.values().stream()
                    .filter(d -> !done.contains(d.node()) && done.containsAll(d.dependsOn()))
                    .map(NodeDefinition::node).findFirst()
                    .orElseThrow(() -> new IllegalStateException("workflow graph contains a cycle"));
            order.add(next);
            done.add(next);
        }
        return order;
    }

    private static NodeDefinition node(Node node, NodeKind kind, Node... deps) {
        Set<Node> dependsOn = EnumSet.noneOf(Node.class);
        dependsOn.addAll(List.of(deps));
        return new NodeDefinition(node, kind, false, dependsOn, outputs -> true, "unconditional");
    }

    private static Object value(Map<String, Object> output, String key) {
        return output == null ? null : output.get(key);
    }

    private static Collection<?> findings(Map<String, Object> output) {
        return value(output, "findings") instanceof Collection<?> c ? c : List.of();
    }
}
