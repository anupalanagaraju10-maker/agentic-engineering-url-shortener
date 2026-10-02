package com.agentic.shortener.workflow.engine;

/** How a stage output was produced (FR-ORC-014, ADR-0003 §9). */
public enum Provenance {
    ACTUAL,
    EXTERNAL,
    FALLBACK
}
