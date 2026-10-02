package com.agentic.shortener.workflow.engine;

/** The 14 workflow DAG nodes (FR-ORC-002), in graph order. */
public enum Node {
    INTAKE,
    UNDERSTAND,
    CLARIFICATION,
    DECOMPOSE,
    IMPACT_ANALYSIS,
    DESIGN,
    DESIGN_APPROVAL,
    IMPLEMENT,
    TEST,
    DOCS,
    SECURITY,
    RELEASE_READINESS,
    RELEASE_APPROVAL,
    FINAL_REPORT
}
