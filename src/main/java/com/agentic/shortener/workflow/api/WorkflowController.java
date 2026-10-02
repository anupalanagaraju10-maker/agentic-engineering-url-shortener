package com.agentic.shortener.workflow.api;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.api.WorkflowRequests.ActorReason;
import com.agentic.shortener.workflow.api.WorkflowRequests.CreateRun;
import com.agentic.shortener.workflow.api.WorkflowRequests.GateDecision;
import com.agentic.shortener.workflow.api.WorkflowRequests.ImplementationEvidence;
import com.agentic.shortener.workflow.api.WorkflowViews.DecisionView;
import com.agentic.shortener.workflow.api.WorkflowViews.EventView;
import com.agentic.shortener.workflow.api.WorkflowViews.RunView;
import com.agentic.shortener.workflow.engine.Actor;
import com.agentic.shortener.workflow.engine.ActorAction;
import com.agentic.shortener.workflow.engine.ActorValidator;
import com.agentic.shortener.workflow.engine.DecisionService;
import com.agentic.shortener.workflow.engine.DecisionService.GateCommand;
import com.agentic.shortener.workflow.engine.ImplementationEvidenceService;
import com.agentic.shortener.workflow.engine.ImplementationEvidenceService.EvidenceCommand;
import com.agentic.shortener.workflow.engine.WorkflowEngine;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow API (contracts/openapi.yaml, FR-ORC-015). No method is transactional: each state change is a
 * short transaction inside the store (H2). Commands advance the run synchronously (ADR-0003).
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final int MAX_REQUIREMENT = 4000;

    private final WorkflowEngine engine;
    private final DecisionService decisions;
    private final ImplementationEvidenceService evidence;
    private final ActorValidator actors;
    private final WorkflowViews views;

    public WorkflowController(WorkflowEngine engine, DecisionService decisions, ImplementationEvidenceService evidence,
            ActorValidator actors, WorkflowViews views) {
        this.engine = engine;
        this.decisions = decisions;
        this.evidence = evidence;
        this.actors = actors;
        this.views = views;
    }

    @PostMapping
    public ResponseEntity<RunView> create(@RequestBody CreateRun request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        Actor actor = new Actor(request.actorType(), request.actorIdentity());
        actors.validate(actor, ActorAction.SUBMIT_REQUIREMENT);
        String requirement = request.requirement();
        if (requirement == null || requirement.isBlank() || requirement.length() > MAX_REQUIREMENT) {
            throw new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                    "requirement must be non-blank and at most " + MAX_REQUIREMENT + " characters");
        }
        if (correlationId != null && correlationId.length() > 64) {
            throw new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                    "X-Correlation-Id must be at most 64 characters");
        }
        if (request.faults() != null && !request.faults().isEmpty()) {
            throw new ApiException(ErrorCategory.FAULT_INJECTION_DISABLED, HttpStatus.BAD_REQUEST,
                    "fault injection is disabled");
        }
        UUID runId = engine.createRun(requirement, actor, correlationId);
        engine.advance(runId);
        return ResponseEntity.status(HttpStatus.CREATED).body(views.run(runId));
    }

    @GetMapping("/{id}")
    public RunView get(@PathVariable UUID id) {
        return views.run(id);
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable UUID id) {
        return views.events(id);
    }

    @GetMapping("/{id}/decisions")
    public List<DecisionView> decisions(@PathVariable UUID id) {
        return views.decisions(id);
    }

    @PostMapping("/{id}/approve")
    public RunView approve(@PathVariable UUID id, @RequestBody GateDecision request) {
        decisions.approve(id, gateCommand(request));
        return views.run(id);
    }

    @PostMapping("/{id}/reject")
    public RunView reject(@PathVariable UUID id, @RequestBody GateDecision request) {
        decisions.reject(id, gateCommand(request));
        return views.run(id);
    }

    @PostMapping("/{id}/terminate")
    public RunView terminate(@PathVariable UUID id, @RequestBody ActorReason request) {
        decisions.terminate(id, new Actor(request.actorType(), request.actorIdentity()), request.reason());
        return views.run(id);
    }

    @PostMapping("/{id}/implementation")
    public RunView implementation(@PathVariable UUID id, @RequestBody ImplementationEvidence request) {
        evidence.record(id, new EvidenceCommand(new Actor(request.actorType(), request.actorIdentity()),
                request.planVersion(), request.summary(), request.changedArtifacts(), request.revision(),
                request.noChangeJustification(), request.requirementIds()));
        return views.run(id);
    }

    private static GateCommand gateCommand(GateDecision request) {
        return new GateCommand(new Actor(request.actorType(), request.actorIdentity()), request.reason(), request.gate(),
                request.planVersion(), request.acceptedRisks());
    }
}
