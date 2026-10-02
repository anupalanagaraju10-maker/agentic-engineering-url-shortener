package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces the actor model (ADR-0004 §1, FR-HUM-006): SYSTEM is never accepted from the API, AGENT is
 * limited to submitting requirements and recording implementation evidence, and reserved identities
 * are refused. Actor types are self-declared (no authentication, EXC-003).
 */
@Component
public class ActorValidator {

    private static final Set<ActorAction> AGENT_ALLOWED =
            EnumSet.of(ActorAction.SUBMIT_REQUIREMENT, ActorAction.RECORD_IMPLEMENTATION);
    private static final Set<String> RESERVED = Set.of("workflow-engine", "system");
    private static final String AGENT_IDENTITY = "claude-code";

    public void validate(Actor actor, ActorAction action) {
        if (actor == null || actor.actorType() == null) {
            throw refused("actorType is required");
        }
        if (actor.actorIdentity() == null || actor.actorIdentity().isBlank()) {
            throw refused("actorIdentity must not be blank");
        }
        if (actor.actorType() == ActorType.SYSTEM) {
            throw refused("SYSTEM actors are written by the workflow engine only");
        }
        String identity = actor.actorIdentity().trim().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(identity)) {
            throw refused("actorIdentity '" + actor.actorIdentity() + "' is reserved");
        }
        if (actor.actorType() == ActorType.HUMAN && identity.equals(AGENT_IDENTITY)) {
            throw refused("'" + AGENT_IDENTITY + "' cannot act as HUMAN");
        }
        if (actor.actorType() == ActorType.AGENT && !AGENT_ALLOWED.contains(action)) {
            throw refused("AGENT actors cannot perform " + action + "; a HUMAN decision is required");
        }
    }

    private static ApiException refused(String reason) {
        return new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST, reason);
    }
}
