package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.rules.AmbiguityRules;
import com.agentic.shortener.workflow.rules.RecordedBehaviorRules;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * HUMAN gate decisions: approve, reject, terminate, resume (FR-HUM-001..007, FR-REL-009, ADR-0004). Every command is checked
 * under the run lock (busy ⇒ 409, H4) in this order: actor, required fields, waiting state, plan version.
 * A refused command is recorded as DECISION_REFUSED before the error is returned; run state is unchanged.
 */
@Service
public class DecisionService {

    private static final Set<Node> APPROVAL_GATES = EnumSet.of(Node.DESIGN_APPROVAL, Node.RELEASE_APPROVAL);
    private static final int MAX_CURRENT_REQUIREMENT = 8000;
    private static final Set<RunStatus> TERMINABLE = EnumSet.of(RunStatus.AWAITING_CLARIFICATION,
            RunStatus.AWAITING_APPROVAL, RunStatus.AWAITING_IMPLEMENTATION, RunStatus.AWAITING_REWORK);

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final ActorValidator actors;
    private final RunLocks locks;
    private final Replanner replanner;

    public DecisionService(WorkflowStore store, WorkflowEngine engine, ActorValidator actors, RunLocks locks,
            Replanner replanner) {
        this.replanner = replanner;
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
            if (!engine.sweepProbeLinks(runId, "terminate")) { // compensation before the run ends (ADR-0005 §6)
                store.safeStop(runId, "compensation failed during terminate", false);
                store.recoveryFailedForOpenIncidents(runId, "SAFE_STOPPED");
                return store.loadRun(runId);
            }
            store.terminateRun(runId, actor, reason, run.getPlanVersion());
            store.recoveryFailedForOpenIncidents(runId, "TERMINATED");
            return store.loadRun(runId);
        });
    }

    /**
     * RESUME (HUMAN only, FR-REL-009): only a recoverable SAFE_STOPPED run. Succeeded stages are kept; only
     * PENDING eligible nodes run (a failed parallel branch re-runs alone).
     */
    public WorkflowRun resume(UUID runId, Actor actor, String reason) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            validateActor(run, actor, ActorAction.RESUME, "resume");
            requireReason(run, actor, reason, "resume");
            if (run.getStatus() != RunStatus.SAFE_STOPPED || !Boolean.TRUE.equals(run.getRecoverable())) {
                throw refuse(run, "resume", actor, ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                        "only a recoverable SAFE_STOPPED run can be resumed (status " + run.getStatus()
                                + ", recoverable " + run.getRecoverable() + ")");
            }
            store.resumeRun(runId, actor, reason, run.getPlanVersion());
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    // ---------------------------------------------------------------- replanning commands (ADR-0004 §6, §8)

    /**
     * CLARIFY (HUMAN) while AWAITING_CLARIFICATION: replans from UNDERSTAND with the CLARIFICATION gate kept
     * SUCCEEDED. A clarification that contradicts an approved requirement is refused (CHANGE_CONTROL_REQUIRED).
     */
    public WorkflowRun clarify(UUID runId, Actor actor, String reason, String clarification, Integer planVersion) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            validateActor(run, actor, ActorAction.CLARIFY, "clarify");
            requireReason(run, actor, reason, "clarify");
            requireText(run, actor, clarification, "clarification", "clarify");
            if (run.getCurrentRequirement().length() + clarification.length() + 16 > MAX_CURRENT_REQUIREMENT) {
                throw refuse(run, "clarify", actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                        "the clarified requirement would exceed " + MAX_CURRENT_REQUIREMENT + " characters");
            }
            requireState(run, actor, "clarify", run.getStatus() == RunStatus.AWAITING_CLARIFICATION);
            requirePlan(run, actor, planVersion, "clarify");
            List<String> changed = RecordedBehaviorRules.changesApprovedRequirements(AmbiguityRules.normalize(clarification));
            if (!changed.isEmpty()) {
                throw refuse(run, "clarify", actor, ErrorCategory.CHANGE_CONTROL_REQUIRED, HttpStatus.CONFLICT,
                        "the clarification contradicts approved requirement(s) " + changed
                                + "; submit it as a requirement change (change control)");
            }
            replanner.replan(runId, new Replanner.Cause("CLARIFICATION", "clarification received", Node.UNDERSTAND,
                    EnumSet.of(Node.CLARIFICATION),
                    plan -> store.recordClarification(runId, actor, reason, clarification, plan)));
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    /** REQUIREMENT CHANGE (HUMAN) on a waiting or recoverable run: replans from UNDERSTAND (every call is material). */
    public WorkflowRun changeRequirement(UUID runId, Actor actor, String reason, String requirement, Integer planVersion) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            validateActor(run, actor, ActorAction.REQUIREMENT_CHANGE, "requirement-change");
            requireReason(run, actor, reason, "requirement-change");
            requireText(run, actor, requirement, "requirement", "requirement-change");
            boolean recoverableStop = run.getStatus() == RunStatus.SAFE_STOPPED && Boolean.TRUE.equals(run.getRecoverable());
            requireState(run, actor, "requirement-change", TERMINABLE.contains(run.getStatus()) || recoverableStop);
            requirePlan(run, actor, planVersion, "requirement-change");
            replanner.replan(runId, new Replanner.Cause("REQUIREMENT_CHANGE", reason, Node.UNDERSTAND, Set.of(),
                    plan -> store.recordRequirementChange(runId, actor, reason, requirement, plan)));
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    /**
     * REWORK (HUMAN) while AWAITING_REWORK: replans from a node at or upstream of the rejected gate or the stage
     * that found the implementation defect (FR-HUM-005, CHK036).
     */
    public WorkflowRun rework(UUID runId, Actor actor, String reason, String fromNode, Integer planVersion) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            validateActor(run, actor, ActorAction.REWORK, "rework");
            requireReason(run, actor, reason, "rework");
            requireState(run, actor, "rework", run.getStatus() == RunStatus.AWAITING_REWORK);
            requirePlan(run, actor, planVersion, "rework");
            Node from = parseNode(fromNode);
            List<Node> failed = store.loadStages(runId).stream().filter(s -> s.getStatus() == StageStatus.FAILED)
                    .map(s -> s.getNode()).toList();
            boolean upstream = from != null && !failed.isEmpty()
                    && failed.stream().allMatch(f -> Replanner.affected(from, Set.of()).contains(f));
            if (!upstream) {
                throw refuse(run, "rework", actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                        "fromNode must be at or upstream of the rejected or failed node " + failed + " (was " + fromNode + ")");
            }
            replanner.replan(runId, new Replanner.Cause("REWORK", reason, from, Set.of(),
                    plan -> store.recordRework(runId, actor, reason, from, plan)));
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    /**
     * Policy-exception decision (HUMAN, FR-POL-004/005). APPROVE needs scope, compensating control and an expiry
     * or review condition; the run continues. REJECT is a non-recoverable safe-stop.
     */
    public WorkflowRun decideException(UUID runId, String checkId, ExceptionCommand command) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            Actor actor = command.actor();
            validateActor(run, actor, ActorAction.POLICY_EXCEPTION, "policy-exception");
            requireReason(run, actor, command.reason(), "policy-exception");
            boolean approve = "APPROVE".equals(command.decision());
            if (!approve && !"REJECT".equals(command.decision())) {
                throw refuse(run, "policy-exception", actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                        "decision must be APPROVE or REJECT");
            }
            if (approve && (blank(command.scope()) || blank(command.compensatingControl())
                    || blank(command.expiresOrReview()))) {
                throw refuse(run, "policy-exception", actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                        "an approved exception requires scope, compensatingControl and expiresOrReview (FR-POL-005)");
            }
            requireState(run, actor, "policy-exception", run.getStatus() == RunStatus.AWAITING_APPROVAL
                    && ("EXCEPTION:" + checkId).equals(run.getPendingAction()));
            requirePlan(run, actor, command.planVersion(), "policy-exception");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("scope", command.scope());
            record.put("compensatingControl", command.compensatingControl());
            record.put("expiresOrReview", command.expiresOrReview());
            store.decideException(runId, checkId, actor, command.reason(), approve, record, run.getPlanVersion());
            if (approve) {
                engine.advance(runId);
            } else {
                store.safeStop(runId, "policy exception " + checkId + " rejected by " + actor.actorIdentity(), false);
            }
            return store.loadRun(runId);
        });
    }

    public record ExceptionCommand(Actor actor, String reason, String decision, Integer planVersion, String scope,
            String compensatingControl, String expiresOrReview) {
    }

    private void requireText(WorkflowRun run, Actor actor, String text, String field, String verb) {
        if (text == null || text.isBlank() || text.length() > 4000) {
            throw refuse(run, verb, actor, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                    field + " must be non-blank and at most 4000 characters");
        }
    }

    private void requireState(WorkflowRun run, Actor actor, String verb, boolean allowed) {
        if (!allowed) {
            throw refuse(run, verb, actor, ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                    verb + " is not allowed while the run is " + run.getStatus() + " (pending " + run.getPendingAction() + ")");
        }
    }

    private void requirePlan(WorkflowRun run, Actor actor, Integer planVersion, String verb) {
        if (planVersion == null || !planVersion.equals(run.getPlanVersion())) {
            throw refuse(run, verb, actor, ErrorCategory.STALE_PLAN_VERSION, HttpStatus.CONFLICT,
                    verb + " is for plan version " + planVersion + " but the run is at " + run.getPlanVersion());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Node parseNode(String value) {
        try {
            return value == null ? null : Node.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
