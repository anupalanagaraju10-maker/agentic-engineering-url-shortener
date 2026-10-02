package com.agentic.shortener.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Idempotency key of a create request (data-model.md {@code idempotency_record}, FR-URL-011). The key is
 * client-assigned, so the entity is always inserted, never merged: a concurrent duplicate key fails on the
 * primary key and is resolved by re-reading (research R14).
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idem_key", length = 100)
    private String key;
    @Column(nullable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR) // CHAR(64): a SHA-256 hex digest always has 64 characters
    private String requestFingerprint;
    @Column(nullable = false)
    private Long linkId;
    @Column(nullable = false)
    private Instant createdAt;
    @Transient
    private boolean isNew;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, String requestFingerprint, Long linkId, Instant createdAt) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.linkId = linkId;
        this.createdAt = createdAt;
        this.isNew = true;
    }

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getLinkId() {
        return linkId;
    }
}
