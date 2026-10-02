package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.workflow.audit.AuditService;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-process DAG engine (ADR-0003) with the reliability model of ADR-0005. Advances a run synchronously, wave
 * by wave, until it reaches a human gate, the external implementation action, a safe-stop or a final state.
 *
 * <p>Thread ownership (H1): pool threads only execute stage logic and return a result; all persistence,
 * including retry, timeout, rollback, compensation and incident events, happens here on the coordinating
 * thread that holds the run lock. Transactions (H2): every state change is its own short transaction inside
 * {@link WorkflowStore}. Cancellation (H3): every attempt has its own token, revoked on timeout; a late
 * result of a revoked attempt is discarded and the engine waits for the worker to stop before compensating.
 *
 * <p>Failure handling: TRANSIENT (incl. timeout) ⇒ attempt rollback, retry with backoff up to the attempt
 * limit, then the fallback where one exists (DOCS), else a recoverable safe-stop. PERMANENT ⇒ stage FAILED;
 * an {@code IMPLEMENTATION_DEFECT} from TEST/SECURITY moves the run to AWAITING_REWORK (CHK036); a blocked
 * RELEASE_READINESS or an inconsistent run state is a non-recoverable safe-stop; any other permanent failure
 * ends the run FAILED. A failed
 * TEST attempt is compensated (probe links deleted); a compensation failure is a non-recoverable safe-stop.
 */
@Component
public class WorkflowEngine {

    private static final Duration WORKER_STOP_GRACE = Duration.ofSeconds(3);

    private final WorkflowStore store;
    private final WorkflowGraph graph;
    private final Map<Node, StageExecutor> executors = new EnumMap<>(Node.class);
    private final RunLocks locks;
    private final List<PostStageHook> hooks;
    private final WorkflowProperties properties;
    private final FaultInjector faults;
    private final CompensationService compensation;
    private final ExecutorService pool;

    @Autowired
    public WorkflowEngine(WorkflowStore store, AuditService audit, ObjectProvider<StageExecutor> executors,
            RunLocks locks, ObjectProvider<PostStageHook> hooks, WorkflowProperties properties, FaultInjector faults,
            CompensationService compensation) {
        this(store, WorkflowGraph.standard(), executors.orderedStream().toList(), locks, hooks.orderedStream().toList(),
                properties, faults, compensation);
    }

    /** Engine without post-stage hooks, fault injection or compensation (engine tests with stub executors). */
    public WorkflowEngine(WorkflowStore store, AuditService audit, WorkflowGraph graph,
            List<StageExecutor> executors, RunLocks locks) {
        this(store, graph, executors, locks, List.of(), WorkflowProperties.defaults(), null, null);
    }

    public WorkflowEngine(WorkflowStore store, AuditService audit, WorkflowGraph graph,
            List<StageExecutor> executors, RunLocks locks, List<PostStageHook> hooks) {
        this(store, graph, executors, locks, hooks, WorkflowProperties.defaults(), null, null);
    }

    private WorkflowEngine(WorkflowStore store, WorkflowGraph graph, List<StageExecutor> executors, RunLocks locks,
            List<PostStageHook> hooks, WorkflowProperties properties, FaultInjector faults,
            CompensationService compensation) {
        this.store = store;
        this.graph = graph;
        this.locks = locks;
        this.hooks = List.copyOf(hooks);
        this.properties = properties;
        this.faults = faults;
        this.compensation = compensation;
        for (StageExecutor executor : executors) {
            if (this.executors.put(executor.node(), executor) != null) {
                throw new IllegalStateException("more than one executor registered for " + executor.node());
            }
        }
        this.pool = Executors.newFixedThreadPool(4, workerThreads());
    }

    public UUID createRun(String requirement, Actor submittedBy, String correlationId) {
        return store.createRun(requirement, submittedBy, correlationId);
    }

    /** Creates a run with a validated fault plan (demonstration only, FR-REL-011). */
    public UUID createRun(String requirement, Actor submittedBy, String correlationId, List<Fault> faultPlan) {
        if (faultPlan == null || faultPlan.isEmpty()) {
            return createRun(requirement, submittedBy, correlationId);
        }
        if (faults == null) {
            throw new IllegalStateException("fault injection is not available in this engine");
        }
        return store.createRun(requirement, submittedBy, correlationId, faults.toJson(faultPlan));
    }

    /** Advances the run as far as allowed. Fails fast with 409 if another command holds the run (H4). */
    public void advance(UUID runId) {
        locks.withLock(runId, () -> {
            advanceLocked(runId);
            return null;
        });
    }

    /** Completes a BLOCKED gate or external action after the caller validated the decision or evidence. */
    public void resolveBlockedNode(UUID runId, Node node, Map<String, Object> output, Provenance provenance) {
        locks.withLock(runId, () -> {
            store.resolveBlocked(runId, node, output, provenance);
            return null;
        });
    }

    /** Housekeeping compensation sweep (terminate, startup). Returns false if the sweep failed. */
    public boolean sweepProbeLinks(UUID runId, String trigger) {
        return compensation == null || compensation.sweep(runId, trigger);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private void advanceLocked(UUID runId) {
        while (true) {
            WorkflowRun run = store.loadRun(runId);
            if (run.getStatus() != RunStatus.RUNNING) {
                return;
            }
            Map<Node, WorkflowStage> stages = store.stageMap(runId);
            if (stages.values().stream().allMatch(s -> isDone(s.getStatus()))) {
                store.completeRun(runId);
                return;
            }
            List<NodeDefinition> eligible = graph.nodes().stream()
                    .filter(d -> stages.get(d.node()).getStatus() == StageStatus.PENDING)
                    .filter(d -> d.dependsOn().stream().allMatch(dep -> isDone(stages.get(dep).getStatus())))
                    .toList();
            Map<Node, Map<String, Object>> outputs = store.outputs(runId);

            Optional<NodeDefinition> undecided = eligible.stream().filter(NodeDefinition::conditional)
                    .filter(d -> !store.hasBranchDecision(runId, d.node(), run.getPlanVersion())).findFirst();
            if (undecided.isPresent()) {
                NodeDefinition d = undecided.get();
                store.recordBranch(runId, d.node(), d.condition().test(outputs), run.getPlanVersion(),
                        d.conditionDescription());
                continue;
            }

            Optional<NodeDefinition> waiting = eligible.stream().filter(d -> d.kind() != NodeKind.AUTOMATED).findFirst();
            if (waiting.isPresent()) {
                store.blockNode(runId, waiting.get());
                return;
            }

            if (eligible.isEmpty()) {
                // FR-REL-007/008: run state found invalid or inconsistent ⇒ non-recoverable safe-stop
                sweepProbeLinks(runId, "inconsistent run state");
                store.safeStop(runId, "INVARIANT: run state is inconsistent (RUNNING, but no node is eligible "
                        + "and not all nodes are done)", false);
                store.recoveryFailedForOpenIncidents(runId, "SAFE_STOPPED");
                return;
            }
            if (!runWave(run, eligible, outputs)) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ one wave

    /** Executes one wave of eligible automated nodes concurrently; returns false if the run stopped. */
    private boolean runWave(WorkflowRun run, List<NodeDefinition> wave, Map<Node, Map<String, Object>> outputs) {
        UUID runId = run.getId();
        WaveOutcome outcome = new WaveOutcome();
        List<Branch> branches = new ArrayList<>();
        for (NodeDefinition definition : wave) {
            Node node = definition.node();
            StageExecutor executor = executors.get(node);
            if (executor == null) {
                store.failStageBeforeStart(runId, node, "INVARIANT", "no executor registered for " + node);
                outcome.hardFailures.add(node);
                continue;
            }
            Optional<String> entryViolation = executor.checkEntry(context(run, outputs, new CancellationToken(),
                    FaultPoint.NONE));
            if (entryViolation.isPresent()) {
                store.failStageBeforeStart(runId, node, "ENTRY_CONDITION", entryViolation.get());
                outcome.hardFailures.add(node);
                continue;
            }
            if (node == Node.TEST && !sweepProbeLinks(runId, "before TEST attempt")) { // H3: leftovers removed
                outcome.compensationFailed = true;
                continue;
            }
            Branch branch = launch(run, node, executor, outputs);
            if (branch != null) {
                branches.add(branch);
            }
        }
        for (Branch branch : branches) {
            drive(run, branch, outputs, outcome);
        }
        return conclude(runId, outcome);
    }

    /** Claims the stage (committed before submission, H2) and submits one attempt to the pool. */
    private Branch launch(WorkflowRun run, Node node, StageExecutor executor, Map<Node, Map<String, Object>> outputs) {
        UUID runId = run.getId();
        if (!store.claimStage(runId, node)) {
            return null;
        }
        int attempt = store.attempts(runId, node);
        CancellationToken token = new CancellationToken();
        FaultPoint point = faults == null ? FaultPoint.NONE : faults.pointFor(run, node, attempt);
        StageContext context = context(run, outputs, token, point);
        CountDownLatch finished = new CountDownLatch(1);
        Instant claimedAt = Instant.now();
        Future<Attempt> future = pool.submit(() -> attempt(executor, context, finished, false));
        return new Branch(node, executor, attempt, token, point, finished, claimedAt,
                claimedAt.plus(properties.stageTimeout()), future, context);
    }

    /** Waits for one branch, retrying, falling back or failing it according to ADR-0005. */
    private void drive(WorkflowRun run, Branch first, Map<Node, Map<String, Object>> outputs, WaveOutcome outcome) {
        UUID runId = run.getId();
        Node node = first.node();
        Integer incident = store.openIncidentFor(runId, node); // e.g. still open from before a resume
        boolean retryRecorded = false;
        Branch branch = first;
        while (true) {
            Awaited awaited = await(branch);
            Attempt a = awaited.attempt();
            StageResult result = a.result();
            if (result.success()) {
                Optional<String> exitViolation = branch.executor().checkExit(result.output());
                if (exitViolation.isEmpty()) {
                    store.completeStage(runId, node, result.output(), result.provenance(), a.startedAt(), a.endedAt(),
                            a.threadName(), a.injected());
                    if (incident != null) {
                        store.recoveryCompleted(runId, node, incident, a.injected());
                    }
                    outcome.hook(runHooks(runId, node));
                    return;
                }
                result = StageResult.failure(FailureClass.PERMANENT, "EXIT_CONDITION", exitViolation.get());
            }
            boolean injected = a.injected();
            if (incident == null) {
                incident = store.failureDetected(runId, node, result.failureClass(), result.failureCode(), injected);
            }
            if (result.failureClass() == FailureClass.TRANSIENT) {
                store.rollbackAttempt(runId, node, branch.attempt(), FailureClass.TRANSIENT, result.failureCode(),
                        result.failureReason(), awaited.timedOut(), a.startedAt(), a.endedAt(), a.threadName(), injected);
                if (node == Node.TEST && compensation != null && !compensation.compensateFailedAttempt(runId, incident,
                        injected, "INJECTED_COMPENSATION_FAILURE".equals(result.failureCode()))) {
                    outcome.compensationFailed = true;
                    return;
                }
                if (branch.attempt() < properties.retry().maxAttempts()) {
                    if (!retryRecorded) {
                        store.recoveryStarted(runId, node, incident, "RETRY", injected);
                        retryRecorded = true;
                    }
                    Duration backoff = properties.retry().before(branch.attempt() + 1);
                    store.retryScheduled(runId, node, branch.attempt() + 1, backoff.toMillis(), incident, injected);
                    sleep(backoff);
                    Branch next = launch(run, node, branch.executor(), outputs);
                    if (next == null) {
                        outcome.hardFailures.add(node);
                        return;
                    }
                    branch = next;
                    continue;
                }
                store.retryExhausted(runId, node, branch.attempt(), incident, injected);
                if (branch.executor().supportsFallback()) {
                    store.recoveryStarted(runId, node, incident, "FALLBACK", injected);
                    if (fallback(run, branch, outputs, incident, injected, outcome)) {
                        return;
                    }
                }
                outcome.exhausted.add(node);
                return;
            }
            store.failStage(runId, node, FailureClass.PERMANENT, result.failureCode(), result.failureReason(),
                    a.startedAt(), a.endedAt(), a.threadName(), injected);
            if (node == Node.TEST && compensation != null
                    && !compensation.compensateFailedAttempt(runId, incident, injected, false)) {
                outcome.compensationFailed = true;
                return;
            }
            if (isImplementationDefect(node, result)) {
                outcome.defects.put(node, incident);
            } else {
                outcome.hardFailures.add(node);
            }
            return;
        }
    }

    /** One more attempt that runs the executor's fallback (DOCS only); true if it succeeded. */
    private boolean fallback(WorkflowRun run, Branch exhausted, Map<Node, Map<String, Object>> outputs, int incident,
            boolean injected, WaveOutcome outcome) {
        UUID runId = run.getId();
        Node node = exhausted.node();
        if (!store.claimStage(runId, node)) {
            return false;
        }
        int attempt = store.attempts(runId, node);
        CancellationToken token = new CancellationToken();
        StageContext context = context(run, outputs, token, FaultPoint.NONE);
        CountDownLatch finished = new CountDownLatch(1);
        Instant claimedAt = Instant.now();
        Future<Attempt> future = pool.submit(() -> attempt(exhausted.executor(), context, finished, true));
        Branch branch = new Branch(node, exhausted.executor(), attempt, token, FaultPoint.NONE, finished, claimedAt,
                claimedAt.plus(properties.stageTimeout()), future, context);
        Awaited awaited = await(branch);
        Attempt a = awaited.attempt();
        StageResult result = a.result();
        if (result.success() && exhausted.executor().checkExit(result.output()).isEmpty()) {
            store.fallbackUsed(runId, node, incident, injected);
            store.completeStage(runId, node, result.output(), Provenance.FALLBACK, a.startedAt(), a.endedAt(),
                    a.threadName(), injected);
            store.recoveryCompleted(runId, node, incident, injected);
            outcome.hook(runHooks(runId, node));
            return true;
        }
        StageResult failure = result.success() ? StageResult.failure(FailureClass.PERMANENT, "EXIT_CONDITION",
                "fallback output does not meet the exit condition") : result;
        store.rollbackAttempt(runId, node, attempt, failure.failureClass(), failure.failureCode(), failure.failureReason(),
                awaited.timedOut(), a.startedAt(), a.endedAt(), a.threadName(), injected);
        return false;
    }

    /** Applies the wave's outcome to the run (precedence: compensation failure, failure, defect, exhaustion). */
    private boolean conclude(UUID runId, WaveOutcome outcome) {
        if (outcome.compensationFailed) {
            store.safeStop(runId, "compensation failed: probe links of this run could not be removed", false);
            store.recoveryFailedForOpenIncidents(runId, "SAFE_STOPPED");
            return false;
        }
        if (!outcome.hardFailures.isEmpty()) {
            if (!sweepProbeLinks(runId, "run end")) {
                store.safeStop(runId, "compensation failed at run end", false);
            } else if (outcome.hardFailures.equals(List.of(Node.RELEASE_READINESS))) {
                store.safeStop(runId, "release readiness blocked; see the RELEASE_READINESS failure", false);
            } else {
                store.failRun(runId, "stage failed: " + outcome.hardFailures + "; see STAGE_FAILED events");
            }
            store.recoveryFailedForOpenIncidents(runId, "RUN_ENDED");
            return false;
        }
        if (!outcome.defects.isEmpty()) {
            store.awaitRework(runId, "implementation defect found by " + outcome.defects.keySet(), outcome.defects);
            return false;
        }
        if (!outcome.exhausted.isEmpty()) {
            if (!sweepProbeLinks(runId, "safe-stop")) {
                store.safeStop(runId, "compensation failed at safe-stop", false);
                store.recoveryFailedForOpenIncidents(runId, "SAFE_STOPPED");
                return false;
            }
            store.safeStop(runId, "retries exhausted at " + outcome.exhausted + " (no fallback)", true);
            return false;
        }
        if (outcome.stop != null) {
            if (outcome.stop.kind() == HookOutcome.Kind.SAFE_STOP) {
                store.safeStop(runId, outcome.stop.reason(), outcome.stop.recoverable());
            } else {
                store.waitForExceptionDecision(runId, outcome.stop.pendingAction(), outcome.stop.payload());
            }
            return false;
        }
        return true;
    }

    /** Post-stage hooks run here, on the coordinating thread (H1); the first non-CONTINUE outcome wins. */
    private HookOutcome runHooks(UUID runId, Node node) {
        HookOutcome first = HookOutcome.proceed();
        for (PostStageHook hook : hooks) {
            HookOutcome outcome = hook.afterStage(store.loadRun(runId), node, store.outputs(runId));
            if (first.kind() == HookOutcome.Kind.CONTINUE && outcome.kind() != HookOutcome.Kind.CONTINUE) {
                first = outcome;
            }
        }
        return first;
    }

    // ------------------------------------------------------------------ attempts (pool side)

    /** Runs on a pool thread: executes stage logic only, never persistence (H1). */
    private static Attempt attempt(StageExecutor executor, StageContext context, CountDownLatch finished,
            boolean fallback) {
        Instant start = Instant.now();
        String thread = Thread.currentThread().getName();
        StageResult result;
        try {
            result = fallback ? executor.fallback(context) : executor.execute(context);
            if (result == null) {
                result = StageResult.failure(FailureClass.PERMANENT, "INVARIANT", "executor returned no result");
            } else if (result.success()) {
                context.faultPoint().reached(); // no-op if the executor already reached it
            }
        } catch (InjectedFault fault) {
            result = StageResult.failure(fault.failureClass(), fault.code(), "injected fault (demonstration)");
        } catch (RuntimeException e) {
            result = StageResult.failure(FailureClass.PERMANENT, "EXECUTOR_ERROR", e.getClass().getSimpleName());
        } finally {
            finished.countDown();
        }
        return new Attempt(result, start, Instant.now(), thread, context.faultPoint().fired());
    }

    /**
     * Waits until the attempt's deadline. On timeout the token is revoked, the future cancelled, and the engine
     * waits for the worker to stop before any compensation, so a late side effect cannot outlive the sweep (H3).
     */
    private Awaited await(Branch branch) {
        long remaining = Math.max(0, Duration.between(Instant.now(), branch.deadline()).toMillis());
        try {
            return new Awaited(branch.future().get(remaining, TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException e) {
            branch.token().revoke();
            branch.future().cancel(true);
            try {
                branch.finished().await(WORKER_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            StageResult timeout = StageResult.failure(FailureClass.TRANSIENT, "TIMEOUT",
                    "attempt exceeded the stage timeout of " + properties.stageTimeout().toMillis() + " ms");
            return new Awaited(new Attempt(timeout, branch.claimedAt(), Instant.now(), "timed-out",
                    branch.point().fired()), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant now = Instant.now();
            return new Awaited(new Attempt(StageResult.failure(FailureClass.TRANSIENT, "INTERRUPTED",
                    "coordinator interrupted"), branch.claimedAt(), now, "unknown", false), false);
        } catch (ExecutionException | CancellationException e) {
            Instant now = Instant.now();
            return new Awaited(new Attempt(StageResult.failure(FailureClass.PERMANENT, "EXECUTOR_ERROR",
                    e.getClass().getSimpleName()), branch.claimedAt(), now, "unknown", false), false);
        }
    }

    private StageContext context(WorkflowRun run, Map<Node, Map<String, Object>> outputs, CancellationToken token,
            FaultPoint point) {
        return new StageContext(run.getId(), run.getCurrentRequirement(), run.getPlanVersion(), outputs, token, point);
    }

    /** CHK036: only the validation stages report defects in the recorded implementation. */
    private static boolean isImplementationDefect(Node node, StageResult result) {
        return (node == Node.TEST || node == Node.SECURITY) && result.failureClass() == FailureClass.PERMANENT
                && "IMPLEMENTATION_DEFECT".equals(result.failureCode());
    }

    private static boolean isDone(StageStatus status) {
        return status == StageStatus.SUCCEEDED || status == StageStatus.SKIPPED;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory workerThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "stage-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** What one wave produced, applied by {@link #conclude}. */
    private static final class WaveOutcome {
        final List<Node> hardFailures = new ArrayList<>();
        final List<Node> exhausted = new ArrayList<>();
        final Map<Node, Integer> defects = new LinkedHashMap<>();
        boolean compensationFailed;
        HookOutcome stop;

        void hook(HookOutcome outcome) {
            if (stop == null && outcome.kind() != HookOutcome.Kind.CONTINUE) {
                stop = outcome;
            }
        }
    }

    private record Branch(Node node, StageExecutor executor, int attempt, CancellationToken token, FaultPoint point,
            CountDownLatch finished, Instant claimedAt, Instant deadline, Future<Attempt> future, StageContext context) {
    }

    private record Attempt(StageResult result, Instant startedAt, Instant endedAt, String threadName,
            boolean injected) {
    }

    private record Awaited(Attempt attempt, boolean timedOut) {
    }
}
