package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.engine.Node.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** T013: the static workflow DAG (FR-ORC-001/002/004/005, plan §Workflow DAG). */
class WorkflowGraphTest {

    private final WorkflowGraph graph = WorkflowGraph.standard();

    @Test
    void hasExactlyTheFourteenSpecifiedNodes() {
        assertThat(graph.nodes()).extracting(NodeDefinition::node).containsExactly(
                INTAKE, UNDERSTAND, CLARIFICATION, DECOMPOSE, IMPACT_ANALYSIS, DESIGN, DESIGN_APPROVAL,
                IMPLEMENT, TEST, DOCS, SECURITY, RELEASE_READINESS, RELEASE_APPROVAL, FINAL_REPORT);
    }

    @Test
    void dependenciesMatchThePlan() {
        assertThat(deps(INTAKE)).isEmpty();
        assertThat(deps(UNDERSTAND)).containsExactly(INTAKE);
        assertThat(deps(CLARIFICATION)).containsExactly(UNDERSTAND);
        assertThat(deps(DECOMPOSE)).containsExactlyInAnyOrder(UNDERSTAND, CLARIFICATION);
        assertThat(deps(IMPACT_ANALYSIS)).containsExactly(DECOMPOSE);
        assertThat(deps(DESIGN)).containsExactlyInAnyOrder(DECOMPOSE, IMPACT_ANALYSIS);
        assertThat(deps(DESIGN_APPROVAL)).containsExactly(DESIGN);
        assertThat(deps(IMPLEMENT)).containsExactly(DESIGN_APPROVAL);
        assertThat(deps(TEST)).containsExactly(IMPLEMENT);
        assertThat(deps(DOCS)).containsExactly(IMPLEMENT);
        assertThat(deps(SECURITY)).containsExactly(IMPLEMENT);
        assertThat(deps(RELEASE_READINESS)).containsExactlyInAnyOrder(TEST, DOCS, SECURITY);
        assertThat(deps(RELEASE_APPROVAL)).containsExactly(RELEASE_READINESS);
        assertThat(deps(FINAL_REPORT)).containsExactly(RELEASE_APPROVAL);
    }

    @Test
    void isAcyclic() {
        List<Node> order = graph.topologicalOrder();
        assertThat(order).hasSize(14);
        Set<Node> seen = new HashSet<>();
        for (Node node : order) {
            assertThat(seen).as("dependencies of %s precede it", node).containsAll(deps(node));
            seen.add(node);
        }
    }

    @Test
    void kindsAndConditionalFlags() {
        assertThat(graph.definition(CLARIFICATION).kind()).isEqualTo(NodeKind.HUMAN_GATE);
        assertThat(graph.definition(DESIGN_APPROVAL).kind()).isEqualTo(NodeKind.HUMAN_GATE);
        assertThat(graph.definition(RELEASE_APPROVAL).kind()).isEqualTo(NodeKind.HUMAN_GATE);
        assertThat(graph.definition(IMPLEMENT).kind()).isEqualTo(NodeKind.EXTERNAL_ACTION);
        for (Node node : List.of(INTAKE, UNDERSTAND, DECOMPOSE, IMPACT_ANALYSIS, DESIGN, TEST, DOCS, SECURITY,
                RELEASE_READINESS, FINAL_REPORT)) {
            assertThat(graph.definition(node).kind()).as(node.name()).isEqualTo(NodeKind.AUTOMATED);
        }
        assertThat(graph.nodes()).filteredOn(NodeDefinition::conditional)
                .extracting(NodeDefinition::node).containsExactlyInAnyOrder(CLARIFICATION, IMPACT_ANALYSIS);
    }

    private Set<Node> deps(Node node) {
        return graph.definition(node).dependsOn();
    }
}
