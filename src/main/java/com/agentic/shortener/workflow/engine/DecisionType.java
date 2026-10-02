package com.agentic.shortener.workflow.engine;

/** Decision lineage record types (data-model.md). */
public enum DecisionType {
    BRANCH,
    APPROVAL,
    REJECTION,
    CLARIFICATION,
    IMPLEMENTATION_EVIDENCE,
    EXCEPTION_APPROVED,
    EXCEPTION_REJECTED,
    REWORK,
    TERMINATION,
    REQUIREMENT_CHANGE,
    RESUME,
    DECISION_INVALIDATED,
    DECISION_REFUSED
}
