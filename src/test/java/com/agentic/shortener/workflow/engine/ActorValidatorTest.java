package com.agentic.shortener.workflow.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.common.ApiException;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T015: actor model rules (ADR-0004 §1, FR-HUM-006). */
class ActorValidatorTest {

    private static final Set<ActorAction> AGENT_ALLOWED =
            EnumSet.of(ActorAction.SUBMIT_REQUIREMENT, ActorAction.RECORD_IMPLEMENTATION);

    private final ActorValidator validator = new ActorValidator();

    @ParameterizedTest
    @EnumSource(ActorAction.class)
    void systemIsNeverAcceptedFromTheApi(ActorAction action) {
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.SYSTEM, "workflow-engine"), action))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @EnumSource(ActorAction.class)
    void humanIsAcceptedForEveryAction(ActorAction action) {
        assertThatCode(() -> validator.validate(new Actor(ActorType.HUMAN, "candidate"), action))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ActorAction.class)
    void agentOnlyForRequirementsAndImplementationEvidence(ActorAction action) {
        Actor agent = new Actor(ActorType.AGENT, "claude-code");
        if (AGENT_ALLOWED.contains(action)) {
            assertThatCode(() -> validator.validate(agent, action)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> validator.validate(agent, action)).isInstanceOf(ApiException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIdentityIsRefused(String identity) {
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.HUMAN, identity), ActorAction.APPROVE_GATE))
                .isInstanceOf(ApiException.class);
    }

    @org.junit.jupiter.api.Test
    void missingActorPartsAreRefused() {
        assertThatThrownBy(() -> validator.validate(null, ActorAction.APPROVE_GATE)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(new Actor(null, "candidate"), ActorAction.APPROVE_GATE))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.HUMAN, null), ActorAction.APPROVE_GATE))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow-engine", "system", "Workflow-Engine", "SYSTEM"})
    void reservedSystemIdentitiesRefusedForHumanAndAgent(String identity) {
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.HUMAN, identity), ActorAction.APPROVE_GATE))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.AGENT, identity), ActorAction.RECORD_IMPLEMENTATION))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void claudeCodeCannotPoseAsHuman() {
        assertThatThrownBy(() -> validator.validate(new Actor(ActorType.HUMAN, "claude-code"), ActorAction.APPROVE_GATE))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> validator.validate(new Actor(ActorType.AGENT, "claude-code"), ActorAction.RECORD_IMPLEMENTATION))
                .doesNotThrowAnyException();
    }
}
