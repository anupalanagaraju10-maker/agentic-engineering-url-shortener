package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.Node;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@link WorkflowStage}: (run_id, node). */
public class StageId implements Serializable {

    private UUID runId;
    private Node node;

    protected StageId() {
    }

    public StageId(UUID runId, Node node) {
        this.runId = runId;
        this.node = node;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StageId other && Objects.equals(runId, other.runId) && node == other.node;
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, node);
    }
}
