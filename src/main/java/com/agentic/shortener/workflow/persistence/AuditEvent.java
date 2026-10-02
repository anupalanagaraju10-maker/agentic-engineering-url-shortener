package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.Node;
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
import org.hibernate.annotations.Immutable;

/**
 * Append-only audit event (FR-OBS-001/002, ADR-0002). Immutable: no setters, no updatable columns, and
 * {@link AuditEventRepository} exposes no update or delete path.
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long id;
    @Column(nullable = false, updatable = false)
    private UUID runId;
    @Column(updatable = false)
    private String correlationId;
    @Column(nullable = false, updatable = false)
    private Integer seq;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditEventType type;
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Node node;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ActorType actorType;
    @Column(nullable = false, updatable = false)
    private String actorIdentity;
    @Column(nullable = false, updatable = false)
    private Integer planVersion;
    @Column(nullable = false, updatable = false)
    private String policyVersion;
    @Column(nullable = false, updatable = false)
    private Boolean injected;
    @Lob
    @Column(updatable = false)
    private String payloadJson;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(UUID runId, String correlationId, int seq, AuditEventType type, Node node, ActorType actorType,
            String actorIdentity, int planVersion, String policyVersion, boolean injected, String payloadJson,
            Instant createdAt) {
        this.runId = runId;
        this.correlationId = correlationId;
        this.seq = seq;
        this.type = type;
        this.node = node;
        this.actorType = actorType;
        this.actorIdentity = actorIdentity;
        this.planVersion = planVersion;
        this.policyVersion = policyVersion;
        this.injected = injected;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Integer getSeq() {
        return seq;
    }

    public AuditEventType getType() {
        return type;
    }

    public Node getNode() {
        return node;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getActorIdentity() {
        return actorIdentity;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public boolean isInjected() {
        return injected;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
