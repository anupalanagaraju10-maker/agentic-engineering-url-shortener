package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.DecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Decision lineage record (FR-ORC-010, data-model.md). New rows supersede old ones; nothing is edited. */
@Entity
@Table(name = "decision")
public class Decision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID runId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionType type;
    private String gate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActorType actorType;
    @Column(nullable = false)
    private String actorIdentity;
    @Column(nullable = false)
    private String reason;
    @Column(nullable = false)
    private Integer planVersion;
    private Long supersedesId;
    @Lob
    private String payloadJson;
    @Column(nullable = false)
    private Instant createdAt;

    protected Decision() {
    }

    public Decision(UUID runId, DecisionType type, String gate, ActorType actorType, String actorIdentity,
            String reason, int planVersion, Long supersedesId, String payloadJson, Instant createdAt) {
        this.runId = runId;
        this.type = type;
        this.gate = gate;
        this.actorType = actorType;
        this.actorIdentity = actorIdentity;
        this.reason = reason;
        this.planVersion = planVersion;
        this.supersedesId = supersedesId;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public DecisionType getType() {
        return type;
    }

    public String getGate() {
        return gate;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getActorIdentity() {
        return actorIdentity;
    }

    public String getReason() {
        return reason;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public Long getSupersedesId() {
        return supersedesId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
