package com.agentic.shortener.workflow.api;

import com.agentic.shortener.workflow.engine.ActorType;
import java.util.List;
import java.util.Map;

/** Request bodies (contracts/openapi.yaml). Validation happens in the services so refusals are recorded. */
public final class WorkflowRequests {

    private WorkflowRequests() {
    }

    public record CreateRun(String requirement, ActorType actorType, String actorIdentity,
            List<Map<String, Object>> faults) {
    }

    public record GateDecision(ActorType actorType, String actorIdentity, String reason, String gate, Integer planVersion,
            List<String> acceptedRisks) {
    }

    public record ActorReason(ActorType actorType, String actorIdentity, String reason) {
    }

    public record ImplementationEvidence(ActorType actorType, String actorIdentity, Integer planVersion, String summary,
            List<String> changedArtifacts, String revision, String noChangeJustification, List<String> requirementIds) {
    }
}
