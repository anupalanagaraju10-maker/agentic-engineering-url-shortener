package com.agentic.shortener.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Short link plus its analytics and optional expiration (data-model.md {@code link}, FR-URL-001..012). The
 * redirect count is only changed by the atomic update in {@link LinkRepository#recordRedirect}.
 * {@code probeRunId} tags transient links created by the workflow's TEST stage; it is a plain id, not a
 * reference into the workflow plane.
 */
@Entity
@Table(name = "link")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 16)
    private String code;
    @Column(nullable = false, length = 2048)
    private String originalUrl;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private long redirectCount;
    private Instant lastRedirectAt;
    private UUID probeRunId;
    private Instant expiresAt;

    protected Link() {
    }

    public Link(String code, String originalUrl, Instant createdAt, UUID probeRunId) {
        this(code, originalUrl, createdAt, probeRunId, null);
    }

    /** {@code expiresAt} null means the link never expires (FR-URL-008). */
    public Link(String code, String originalUrl, Instant createdAt, UUID probeRunId, Instant expiresAt) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.probeRunId = probeRunId;
        this.expiresAt = expiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Expired once the expiration time has been reached; a link without one never expires. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getRedirectCount() {
        return redirectCount;
    }

    public Instant getLastRedirectAt() {
        return lastRedirectAt;
    }

    public UUID getProbeRunId() {
        return probeRunId;
    }
}
