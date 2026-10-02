package com.agentic.shortener.workflow.engine;

/** Actions an API caller may request; used to enforce the actor model (ADR-0004 §1). */
public enum ActorAction {
    SUBMIT_REQUIREMENT,
    RECORD_IMPLEMENTATION,
    APPROVE_GATE,
    REJECT_GATE,
    CLARIFY,
    REWORK,
    TERMINATE,
    REQUIREMENT_CHANGE,
    POLICY_EXCEPTION,
    RESUME
}
