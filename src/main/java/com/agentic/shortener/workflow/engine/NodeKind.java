package com.agentic.shortener.workflow.engine;

/** How a node makes progress (ADR-0003). */
public enum NodeKind {
    /** Deterministic executor run by the engine. */
    AUTOMATED,
    /** Waits for a recorded HUMAN decision. */
    HUMAN_GATE,
    /** Waits for evidence of work performed outside the application (IMPLEMENT). */
    EXTERNAL_ACTION
}
