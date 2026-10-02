package com.agentic.shortener.workflow.engine;

/** Who performed an action: actor type plus identity (ADR-0004 §1). */
public record Actor(ActorType actorType, String actorIdentity) {

    /** The engine itself; written only by the system, never accepted from the API. */
    public static final Actor ENGINE = new Actor(ActorType.SYSTEM, "workflow-engine");
}
