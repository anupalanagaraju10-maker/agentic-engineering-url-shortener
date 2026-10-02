package com.agentic.shortener.workflow.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Append-only access to the audit trail (ADR-0002). Extends the marker {@link Repository}, so it inherits
 * no delete or update methods; {@link #save} is used solely by {@code AuditService} for new events.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent newEvent);

    List<AuditEvent> findByRunIdOrderBySeqAsc(UUID runId);

    @Query("select coalesce(max(e.seq), 0) from AuditEvent e where e.runId = :runId")
    int maxSeq(@Param("runId") UUID runId);
}
