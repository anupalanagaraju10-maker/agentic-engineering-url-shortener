package com.agentic.shortener.workflow.audit;

import com.agentic.shortener.workflow.engine.Actor;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single writer of the append-only audit trail (FR-OBS-001/002). Each event copies the run's
 * correlation id, plan version and policy version. {@code seq} allocation (max + 1, then insert) is
 * serialized per run as a safety net (H1); normally only the coordinating thread writes. When called
 * inside a caller's transaction the insert joins it, so an event is never committed without the state
 * change it describes.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Map<UUID, ReentrantLock> seqLocks = new ConcurrentHashMap<>();

    public AuditService(AuditEventRepository events, JdbcTemplate jdbc, PlatformTransactionManager txManager,
            ObjectMapper json) {
        this.events = events;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
    }

    public AuditEvent append(UUID runId, AuditEventType type, Node node, Actor actor, Map<String, Object> payload,
            boolean injected) {
        ReentrantLock lock = seqLocks.computeIfAbsent(runId, id -> new ReentrantLock());
        lock.lock();
        try {
            return tx.execute(status -> {
                Map<String, Object> run = jdbc.queryForMap(
                        "select correlation_id, plan_version, policy_version from workflow_run where id = ?", runId);
                int seq = events.maxSeq(runId) + 1;
                return events.save(new AuditEvent(runId, (String) run.get("correlation_id"), seq, type, node,
                        actor.actorType(), actor.actorIdentity(), ((Number) run.get("plan_version")).intValue(),
                        (String) run.get("policy_version"), injected, toJson(payload), Instant.now()));
            });
        } finally {
            lock.unlock();
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit payload is not serializable", e);
        }
    }
}
