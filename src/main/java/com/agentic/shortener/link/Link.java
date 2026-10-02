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
 * Short link plus its analytics (data-model.md {@code link}, FR-URL-001..012). The redirect count is only
 * changed by the atomic update in {@link LinkRepository#recordRedirect}. {@code probeRunId} tags transient
 * links created by the workflow's TEST stage; it is a plain id, not a reference into the workflow plane.
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

    protected Link() {
    }

    public Link(String code, String originalUrl, Instant createdAt, UUID probeRunId) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.probeRunId = probeRunId;
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
