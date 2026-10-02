package com.agentic.shortener.workflow.engine;

/** Durable stage states (FR-ORC-011, research R8). */
public enum StageStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
