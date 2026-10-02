package com.agentic.shortener.workflow.persistence;

import com.agentic.shortener.workflow.engine.DecisionType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Decision lineage records (FR-ORC-010). */
public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByRunIdOrderByIdAsc(UUID runId);

    boolean existsByRunIdAndTypeAndGateAndPlanVersion(UUID runId, DecisionType type, String gate, Integer planVersion);
}
