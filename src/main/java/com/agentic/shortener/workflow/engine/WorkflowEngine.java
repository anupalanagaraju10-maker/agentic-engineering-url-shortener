package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.workflow.audit.AuditService;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-process DAG engine (ADR-0003). Advances a run synchronously, wave by wave, until it reaches a human
 * gate, the external implementation action, or a final state.
 *
 * <p>Thread ownership (H1): pool threads only execute stage logic and return a {@link StageResult}; all
 * persistence happens here, on the coordinating thread that holds the run lock. Transactions (H2): every
 * state change is its own short transaction inside {@link WorkflowStore}; nothing wraps a whole command.
 *
 * <p>Interim failure policy (Phase 2): any stage failure ends the run FAILED. Retry, rework routing and
 * safe-stop are added in Phases 5–6.
 */
@Component
public class WorkflowEngine {

    private final WorkflowStore store;
    private final WorkflowGraph graph;
    private final Map<Node, StageExecutor> executors = new EnumMap<>(Node.class);
    private final RunLocks locks;
    private final List<PostStageHook> hooks;
    private final ExecutorService pool;

    @Autowired
    public WorkflowEngine(WorkflowStore store, AuditService audit, ObjectProvider<StageExecutor> executors,
            RunLocks locks, ObjectProvider<PostStageHook> hooks) {
        this(store, audit, WorkflowGraph.standard(), executors.orderedStream().toList(), locks,
                hooks.orderedStream().toList());
    }

    /** Engine without post-stage hooks (used by engine tests with stub executors). */
    public WorkflowEngine(WorkflowStore store, AuditService audit, WorkflowGraph graph,
            List<StageExecutor> executors, RunLocks locks) {
        this(store, audit, graph, executors, locks, List.of());
    }

    public WorkflowEngine(WorkflowStore store, AuditService audit, WorkflowGraph graph,
            List<StageExecutor> executors, RunLocks locks, List<PostStageHook> hooks) {
        this.store = store;
        this.graph = graph;
        this.locks = locks;
        this.hooks = List.copyOf(hooks);
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
                store.failRun(runId, "INVARIANT: run is RUNNING but no node is eligible");
                return;
            }
            if (!runWave(run, eligible, outputs)) {
                return;
            }
        }
    }

    /** Executes one wave of eligible automated nodes concurrently; returns false if the run stopped. */
    private boolean runWave(WorkflowRun run, List<NodeDefinition> wave, Map<Node, Map<String, Object>> outputs) {
        UUID runId = run.getId();
        boolean failed = false;
        HookOutcome stop = null;
        List<Submitted> submitted = new ArrayList<>();
        for (NodeDefinition definition : wave) {
            Node node = definition.node();
            StageExecutor executor = executors.get(node);
            StageContext context = new StageContext(runId, run.getCurrentRequirement(), run.getPlanVersion(),
                    outputs, new CancellationToken());
            if (executor == null) {
                store.failStageBeforeStart(runId, node, "INVARIANT", "no executor registered for " + node);
                failed = true;
                continue;
            }
            Optional<String> entryViolation = executor.checkEntry(context);
            if (entryViolation.isPresent()) {
                store.failStageBeforeStart(runId, node, "ENTRY_CONDITION", entryViolation.get());
                failed = true;
                continue;
            }
            if (store.claimStage(runId, node)) {
                submitted.add(new Submitted(node, executor, pool.submit(() -> attempt(executor, context))));
            }
        }
        for (Submitted s : submitted) {
            Attempt a = await(s.future());
            StageResult result = a.result();
            if (result.success()) {
                Optional<String> exitViolation = s.executor().checkExit(result.output());
                if (exitViolation.isPresent()) {
                    store.failStage(runId, s.node(), FailureClass.PERMANENT, "EXIT_CONDITION", exitViolation.get(),
                            a.startedAt(), a.endedAt(), a.threadName());
                    failed = true;
                } else {
                    store.completeStage(runId, s.node(), result.output(), result.provenance(), a.startedAt(),
                            a.endedAt(), a.threadName());
                    HookOutcome outcome = runHooks(runId, s.node());
                    if (stop == null && outcome.kind() != HookOutcome.Kind.CONTINUE) {
                        stop = outcome;
                    }
                }
            } else {
                store.failStage(runId, s.node(), result.failureClass(), result.failureCode(), result.failureReason(),
                        a.startedAt(), a.endedAt(), a.threadName());
                failed = true;
            }
        }
        if (failed) {
            store.failRun(runId, "a stage failed; see STAGE_FAILED events");
            return false;
        }
        if (stop != null) {
            if (stop.kind() == HookOutcome.Kind.SAFE_STOP) {
                store.safeStop(runId, stop.reason(), stop.recoverable());
            } else {
                store.waitForExceptionDecision(runId, stop.pendingAction(), stop.payload());
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

    /** Runs on a pool thread: executes stage logic only, never persistence (H1). */
    private static Attempt attempt(StageExecutor executor, StageContext context) {
        Instant start = Instant.now();
        String thread = Thread.currentThread().getName();
        StageResult result;
        try {
            result = executor.execute(context);
            if (result == null) {
                result = StageResult.failure(FailureClass.PERMANENT, "INVARIANT", "executor returned no result");
            }
        } catch (RuntimeException e) {
            result = StageResult.failure(FailureClass.PERMANENT, "EXECUTOR_ERROR", e.getClass().getSimpleName());
        }
        return new Attempt(result, start, Instant.now(), thread);
    }

    private static Attempt await(Future<Attempt> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant now = Instant.now();
            return new Attempt(StageResult.failure(FailureClass.TRANSIENT, "INTERRUPTED", "coordinator interrupted"),
                    now, now, Thread.currentThread().getName());
        } catch (ExecutionException e) {
            Instant now = Instant.now();
            return new Attempt(StageResult.failure(FailureClass.PERMANENT, "EXECUTOR_ERROR",
                    e.getCause().getClass().getSimpleName()), now, now, "unknown");
        }
    }

    private static boolean isDone(StageStatus status) {
        return status == StageStatus.SUCCEEDED || status == StageStatus.SKIPPED;
    }

    private static ThreadFactory workerThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "stage-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record Submitted(Node node, StageExecutor executor, Future<Attempt> future) {
    }

    private record Attempt(StageResult result, Instant startedAt, Instant endedAt, String threadName) {
    }
}
