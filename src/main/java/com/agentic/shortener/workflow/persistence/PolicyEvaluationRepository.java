package com.agentic.shortener.workflow.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Policy evaluation results (FR-POL-001..006). */
public interface PolicyEvaluationRepository extends JpaRepository<PolicyEvaluation, Long> {

    List<PolicyEvaluation> findByRunIdOrderByIdAsc(UUID runId);
}
