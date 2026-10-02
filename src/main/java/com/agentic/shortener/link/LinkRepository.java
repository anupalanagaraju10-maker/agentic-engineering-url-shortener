package com.agentic.shortener.link;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByCode(String code);

    boolean existsByCode(String code);

    /** Atomic increment: concurrent redirects never lose a count (FR-URL-012, PVT-008). */
    @Transactional
    @Modifying
    @Query("UPDATE Link l SET l.redirectCount = l.redirectCount + 1, l.lastRedirectAt = :now WHERE l.id = :id")
    int recordRedirect(@Param("id") Long id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("DELETE FROM Link l WHERE l.probeRunId = :runId")
    int deleteByProbeRunId(@Param("runId") UUID runId);
}
