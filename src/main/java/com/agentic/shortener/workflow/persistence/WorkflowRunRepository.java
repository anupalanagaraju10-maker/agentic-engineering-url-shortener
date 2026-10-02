package com.agentic.shortener.workflow.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to runs; writes go through {@code WorkflowStore} on the coordinating thread (H1). */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
}
