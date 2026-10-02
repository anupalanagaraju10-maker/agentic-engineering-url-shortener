package com.agentic.shortener.workflow.engine;

/** Demonstration fault types (FR-REL-011, ADR-0005 §10). */
public enum FaultType {
    TRANSIENT,
    PERMANENT,
    TIMEOUT,
    DELAY,
    COMPENSATION_FAILURE
}
