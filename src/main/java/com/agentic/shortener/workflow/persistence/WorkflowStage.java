package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Current state of one DAG node in one run (data-model.md). Read-only from JPA; written via the store. */
@Entity
@Table(name = "workflow_stage")
@IdClass(StageId.class)
public class WorkflowStage {

    @Id
    private UUID runId;
    @Id
    @Enumerated(EnumType.STRING)
    private Node node;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status;
    @Column(nullable = false)
    private Integer attempts;
    private Instant startedAt;
    private Instant endedAt;
    private String threadName;
    @Lob
    private String outputJson;
    @Enumerated(EnumType.STRING)
    private Provenance provenance;
    @Enumerated(EnumType.STRING)
    private FailureClass failureClass;
    private String failureCode;
    private String failureReason;
    @Column(nullable = false)
    private Integer planVersion;

    protected WorkflowStage() {
    }

    public UUID getRunId() {
        return runId;
    }

    public Node getNode() {
        return node;
    }

    public StageStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public FailureClass getFailureClass() {
        return failureClass;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }
}
