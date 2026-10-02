package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * HUMAN gate decisions: approve, reject, terminate (FR-HUM-001..007, ADR-0004). Every command is checked
 * under the run lock (busy ⇒ 409, H4) in this order: actor, required fields, waiting state, plan version.
 * A refused command is recorded as DECISION_REFUSED before the error is returned; run state is unchanged.
 */
@Service
public class DecisionService {

    private static final Set<Node> APPROVAL_GATES = EnumSet.of(Node.DESIGN_APPROVAL, Node.RELEASE_APPROVAL);
    private static final Set<RunStatus> TERMINABLE = EnumSet.of(RunStatus.AWAITING_CLARIFICATION,
            RunStatus.AWAITING_APPROVAL, RunStatus.AWAITING_IMPLEMENTATION, RunStatus.AWAITING_REWORK);

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final ActorValidator actors;
    private final RunLocks locks;

    public DecisionService(WorkflowStore store, WorkflowEngine engine, ActorValidator actors, RunLocks locks) {
        this.store = store;
        this.engine = engine;
        this.actors = actors;
        this.locks = locks;
    }

    public record GateCommand(Actor actor, String reason, String gate, Integer planVersion, List<String> acceptedRisks) {
    }

    public WorkflowRun approve(UUID runId, GateCommand command) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            Node gate = checkGateCommand(run, command, ActorAction.APPROVE_GATE, "approve");
            store.approveGate(runId, gate, command.actor(), command.reason(), command.planVersion(),
                    command.acceptedRisks());
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    public WorkflowRun reject(UUID runId, GateCommand command) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            Node gate = checkGateCommand(run, command, ActorAction.REJECT_GATE, "reject");
            store.rejectGate(runId, gate, command.actor(), command.reason(), command.planVersion());
            return store.loadRun(runId);
        });
    }

    /** Allowed from every AWAITING_* state and from a recoverable SAFE_STOPPED run (CHK002). */
    public WorkflowRun terminate(UUID runId, Actor actor, String reason) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            validateActor(run, actor, ActorAction.TERMINATE, "terminate");
            requireReason(run, actor, reason, "terminate");
            boolean recoverableStop = run.getStatus() == RunStatus.SAFE_STOPPED && Boolean.TRUE.equals(run.getRecoverable());
            if (!TERMINABLE.contains(run.getStatus()) && !recoverableStop) {
                throw refuse(run, "terminate", actor, ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                        "run in status " + run.getStatus() + " cannot be terminated");
            }
            store.terminateRun(runId, actor, reason, run.getPlanVersion());
            return store.loadRun(runId);
        });
    }

    private Node checkGateCommand(WorkflowRun run, GateCommand command, ActorAction action, String verb) {
        validateActor(run, command.actor(), action, verb);
        requireReason(run, command.actor(), command.reason(), verb);
        Node gate = parseGate(command.gate());
        if (gate == null || command.planVersion() == null) {
            throw refuse(run, verb, command.actor(), ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                    "gate (DESIGN_APPROVAL or RELEASE_APPROVAL) and planVersion are required");
        }
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL || !("APPROVE:" + gate.name()).equals(run.getPendingAction())) {
            throw refuse(run, verb, command.actor(), ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                    "run is not waiting at " + gate + " (status " + run.getStatus() + ", pending "
                            + run.getPendingAction() + ")");
        }
        if (!command.planVersion().equals(run.getPlanVersion())) {
            throw refuse(run, verb, command.actor(), ErrorCategory.STALE_PLAN_VERSION, HttpStatus.CONFLICT,
                    "decision is for plan version " + command.planVersion() + " but the run is at "
                            + run.getPlanVersion());
        }
        return gate;
    }

    void validateActor(WorkflowRun run, Actor actor, ActorAction action, String verb) {
        try {
            actors.validate(actor, action);
        } catch (ApiException e) {
            store.refuseDecision(run.getId(), verb, actor, e.getMessage(), run.getPlanVersion());
            throw e;
        }
    }

    private void requireReason(WorkflowRun run, Actor actor, String reason, String verb) {
        if (reason == null || reason.isBlank()) {
            throw refuse(run, verb, actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST, "reason is required");
        }
    }

    ApiException refuse(WorkflowRun run, String verb, Actor actor, ErrorCategory category, HttpStatus status,
            String reason) {
        store.refuseDecision(run.getId(), verb, actor, reason, run.getPlanVersion());
        return new ApiException(category, status, reason);
    }

    private static Node parseGate(String gate) {
        if (gate == null) {
            return null;
        }
        try {
            Node node = Node.valueOf(gate);
            return APPROVAL_GATES.contains(node) ? node : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
