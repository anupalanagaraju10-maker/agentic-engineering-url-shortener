package com.agentic.shortener.workflow.engine;

/** Durable run states (FR-ORC-011, research R8). */
public enum RunStatus {
    RUNNING,
    AWAITING_CLARIFICATION,
    AWAITING_APPROVAL,
    AWAITING_IMPLEMENTATION,
    AWAITING_REWORK,
    SAFE_STOPPED,
    COMPLETED,
    FAILED
}
