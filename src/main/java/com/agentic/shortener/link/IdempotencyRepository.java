package com.agentic.shortener.link;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /** Keys of a run's probe links must go before the links themselves (foreign key). */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.linkId IN (SELECT l.id FROM Link l WHERE l.probeRunId = :runId)")
    int deleteForProbeRun(@Param("runId") UUID runId);
}
