package com.agentic.shortener.workflow.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to stage rows; writes go through {@code WorkflowStore} on the coordinating thread (H1). */
public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, StageId> {

    List<WorkflowStage> findByRunId(UUID runId);
}
