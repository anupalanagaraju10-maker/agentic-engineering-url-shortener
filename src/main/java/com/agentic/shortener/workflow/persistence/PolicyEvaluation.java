package com.agentic.shortener.workflow.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One policy check result (FR-POL-001..006, data-model.md). Populated from Phase 3 (PolicyEvaluator). */
@Entity
@Table(name = "policy_evaluation")
public class PolicyEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID runId;
    @Column(nullable = false)
    private String policyVersion;
    @Column(nullable = false)
    private String checkId;
    @Column(nullable = false)
    private String domain;
    @Column(nullable = false)
    private String node;
    @Column(nullable = false)
    private Boolean mandatory;
    @Column(nullable = false)
    private String result;
    private String reason;
    @Column(nullable = false)
    private Integer planVersion;
    private Long resolutionDecisionId;
    @Column(nullable = false)
    private Instant createdAt;

    protected PolicyEvaluation() {
    }

    public PolicyEvaluation(UUID runId, String policyVersion, String checkId, String domain, String node,
            boolean mandatory, String result, String reason, int planVersion, Instant createdAt) {
        this.runId = runId;
        this.policyVersion = policyVersion;
        this.checkId = checkId;
        this.domain = domain;
        this.node = node;
        this.mandatory = mandatory;
        this.result = result;
        this.reason = reason;
        this.planVersion = planVersion;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getCheckId() {
        return checkId;
    }

    public String getDomain() {
        return domain;
    }

    public String getNode() {
        return node;
    }

    public Boolean getMandatory() {
        return mandatory;
    }

    public String getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public Long getResolutionDecisionId() {
        return resolutionDecisionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
