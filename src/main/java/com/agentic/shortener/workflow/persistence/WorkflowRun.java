package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Workflow run (data-model.md). Status changes are written by the coordinating thread only (H1). */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private UUID id;
    private String correlationId;
    @Column(nullable = false)
    private String originalRequirement;
    private String currentRequirement;
    @Lob
    private String normalizedJson;
    private String changeType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;
    private String pendingAction;
    @Column(nullable = false)
    private Integer planVersion;
    @Column(nullable = false)
    private String policyVersion;
    private Boolean recoverable;
    private String stopReason;
    @Lob
    private String faultPlanJson;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant endedAt;
    @Version
    private Long version;

    protected WorkflowRun() {
    }

    public UUID getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getOriginalRequirement() {
        return originalRequirement;
    }

    public String getCurrentRequirement() {
        return currentRequirement;
    }

    public String getNormalizedJson() {
        return normalizedJson;
    }

    public String getChangeType() {
        return changeType;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getPendingAction() {
        return pendingAction;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Boolean getRecoverable() {
        return recoverable;
    }

    public String getStopReason() {
        return stopReason;
    }

    public String getFaultPlanJson() {
        return faultPlanJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Long getVersion() {
        return version;
    }
}
