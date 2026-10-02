package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.engine.Node.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.audit.AuditService;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.DecisionRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * T016: orchestration engine behaviour with stub executors (FR-ORC-003..008, SC-002, ADR-0003, H1, H2,
 * H4). The stubs do no persistence work, so this test isolates the engine.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowEngineTest {

    private static final Actor CANDIDATE = new Actor(ActorType.HUMAN, "candidate");

    @Autowired
    private WorkflowStore store;
    @Autowired
    private AuditService audit;
    @Autowired
    private RunLocks locks;
    @Autowired
    private AuditEventRepository events;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private JdbcTemplate jdbc;

    private final Map<Node, StubExecutor> stubs = new EnumMap<>(Node.class);
    private WorkflowEngine engine;

    private WorkflowEngine engine() {
        if (engine == null) {
            engine = new WorkflowEngine(store, audit, WorkflowGraph.standard(), new ArrayList<>(stubs.values()), locks);
        }
        return engine;
    }

    @AfterEach
    void shutdown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    private void defaultStubs(String changeType, List<String> findings) {
        for (Node node : List.of(INTAKE, DECOMPOSE, IMPACT_ANALYSIS, DESIGN, TEST, DOCS, SECURITY,
                RELEASE_READINESS, FINAL_REPORT)) {
            stubs.put(node, StubExecutor.succeeding(node));
        }
        stubs.put(UNDERSTAND, new StubExecutor(UNDERSTAND,
                ctx -> StageResult.success(Map.of("changeType", changeType, "findings", findings), Provenance.ACTUAL)));
    }

    private UUID newRun() {
        return engine().createRun("Create a short link", CANDIDATE, null);
    }

    @Test
    void greenfieldRunsSequentiallyAndStopsAtDesignApproval() {
        defaultStubs("GREENFIELD", List.of());
        UUID runId = newRun();

        engine().advance(runId);

        WorkflowRun run = store.loadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.getPendingAction()).isEqualTo("APPROVE:DESIGN_APPROVAL");
        Map<Node, WorkflowStage> s = stages(runId);
        assertThat(statusOf(s, INTAKE, UNDERSTAND, DECOMPOSE, DESIGN)).containsOnly(StageStatus.SUCCEEDED);
        assertThat(statusOf(s, CLARIFICATION, IMPACT_ANALYSIS)).containsOnly(StageStatus.SKIPPED);
        assertThat(s.get(DESIGN_APPROVAL).getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(statusOf(s, IMPLEMENT, TEST, DOCS, SECURITY, RELEASE_READINESS, RELEASE_APPROVAL, FINAL_REPORT))
                .containsOnly(StageStatus.PENDING);
        // sequential path: each stage starts only after its dependency ended
        assertThat(s.get(UNDERSTAND).getStartedAt()).isAfterOrEqualTo(s.get(INTAKE).getEndedAt());
        assertThat(s.get(DECOMPOSE).getStartedAt()).isAfterOrEqualTo(s.get(UNDERSTAND).getEndedAt());
        assertThat(s.get(DESIGN).getStartedAt()).isAfterOrEqualTo(s.get(DECOMPOSE).getEndedAt());
        // branch decisions are recorded with a reason and audited
        List<Decision> branch = decisions.findByRunIdOrderByIdAsc(runId).stream()
                .filter(d -> d.getType() == DecisionType.BRANCH).toList();
        assertThat(branch).extracting(Decision::getGate).containsExactly("CLARIFICATION", "IMPACT_ANALYSIS");
        assertThat(branch).allSatisfy(d -> {
            assertThat(d.getActorType()).isEqualTo(ActorType.SYSTEM);
            assertThat(d.getReason()).isNotBlank();
        });
        assertThat(eventTypes(runId)).contains(AuditEventType.BRANCH_TAKEN, AuditEventType.APPROVAL_REQUESTED);
    }

    @Test
    void ambiguityBlocksAtClarificationAndNothingDownstreamStarts() {
        defaultStubs("GREENFIELD", List.of("AMB-R2"));
        UUID runId = newRun();

        engine().advance(runId);

        assertThat(store.loadRun(runId).getStatus()).isEqualTo(RunStatus.AWAITING_CLARIFICATION);
        assertThat(store.loadRun(runId).getPendingAction()).isEqualTo("CLARIFY");
        Map<Node, WorkflowStage> s = stages(runId);
        assertThat(s.get(CLARIFICATION).getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(s.get(DECOMPOSE).getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(stubs.get(DECOMPOSE).calls.get()).isZero();
        assertThat(eventTypes(runId)).contains(AuditEventType.CLARIFICATION_REQUESTED);
    }

    @Test
    void brownfieldTakesTheImpactAnalysisBranch() {
        defaultStubs("BROWNFIELD", List.of());
        UUID runId = newRun();

        engine().advance(runId);

        assertThat(stages(runId).get(IMPACT_ANALYSIS).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stubs.get(IMPACT_ANALYSIS).calls.get()).isEqualTo(1);
    }

    @Test
    void parallelGroupOverlapsAndJoinWaitsThenReleaseCompletes() {
        defaultStubs("GREENFIELD", List.of());
        for (Node node : List.of(TEST, DOCS, SECURITY)) {
            stubs.put(node, StubExecutor.sleeping(node, 200));
        }
        UUID runId = newRun();
        engine().advance(runId);
        engine().resolveBlockedNode(runId, DESIGN_APPROVAL, Map.of("approvedBy", "candidate"), Provenance.EXTERNAL);
        engine().advance(runId);

        assertThat(store.loadRun(runId).getStatus()).isEqualTo(RunStatus.AWAITING_IMPLEMENTATION);
        assertThat(store.loadRun(runId).getPendingAction()).isEqualTo("RECORD_IMPLEMENTATION");
        assertThat(eventTypes(runId)).contains(AuditEventType.IMPLEMENTATION_REQUESTED);

        engine().resolveBlockedNode(runId, IMPLEMENT, Map.of("revision", "abc1234"), Provenance.EXTERNAL);
        engine().advance(runId);

        Map<Node, WorkflowStage> s = stages(runId);
        List<WorkflowStage> group = List.of(s.get(TEST), s.get(DOCS), s.get(SECURITY));
        Instant maxStart = group.stream().map(WorkflowStage::getStartedAt).max(Instant::compareTo).orElseThrow();
        Instant minEnd = group.stream().map(WorkflowStage::getEndedAt).min(Instant::compareTo).orElseThrow();
        Instant maxEnd = group.stream().map(WorkflowStage::getEndedAt).max(Instant::compareTo).orElseThrow();
        assertThat(maxStart).as("parallel intervals overlap").isBefore(minEnd);
        assertThat(group).extracting(WorkflowStage::getThreadName).doesNotHaveDuplicates();
        assertThat(s.get(RELEASE_READINESS).getStartedAt()).as("join waits for all three").isAfterOrEqualTo(maxEnd);
        assertThat(s.get(RELEASE_APPROVAL).getStatus()).isEqualTo(StageStatus.BLOCKED);

        engine().resolveBlockedNode(runId, RELEASE_APPROVAL, Map.of(), Provenance.EXTERNAL);
        engine().advance(runId);

        WorkflowRun done = store.loadRun(runId);
        assertThat(done.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.getEndedAt()).isNotNull();
        assertThat(eventTypes(runId)).contains(AuditEventType.RUN_COMPLETED);
    }

    @Test
    void joinDoesNotStartWhenAParallelBranchFails() {
        defaultStubs("GREENFIELD", List.of());
        stubs.put(SECURITY, new StubExecutor(SECURITY,
                ctx -> StageResult.failure(FailureClass.PERMANENT, "INVARIANT", "stub failure")));
        UUID runId = newRun();
        engine().advance(runId);
        engine().resolveBlockedNode(runId, DESIGN_APPROVAL, Map.of(), Provenance.EXTERNAL);
        engine().advance(runId);
        engine().resolveBlockedNode(runId, IMPLEMENT, Map.of(), Provenance.EXTERNAL);
        engine().advance(runId);

        Map<Node, WorkflowStage> s = stages(runId);
        assertThat(s.get(SECURITY).getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(s.get(TEST).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(s.get(DOCS).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(s.get(RELEASE_READINESS).getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(stubs.get(RELEASE_READINESS).calls.get()).isZero();
    }

    @Test
    void exitConditionFailureIsNotMarkedSucceeded() {
        defaultStubs("GREENFIELD", List.of());
        StubExecutor design = StubExecutor.succeeding(DESIGN);
        design.exitCheck = output -> Optional.of("design maps no task");
        stubs.put(DESIGN, design);
        UUID runId = newRun();

        engine().advance(runId);

        WorkflowStage stage = stages(runId).get(DESIGN);
        assertThat(stage.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stage.getFailureCode()).isEqualTo("EXIT_CONDITION");
        assertThat(stages(runId).get(DESIGN_APPROVAL).getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    void entryConditionFailureNeverStartsTheStage() {
        defaultStubs("GREENFIELD", List.of());
        StubExecutor intake = StubExecutor.succeeding(INTAKE);
        intake.entryCheck = ctx -> Optional.of("requirement is blank");
        stubs.put(INTAKE, intake);
        UUID runId = newRun();

        engine().advance(runId);

        WorkflowStage stage = stages(runId).get(INTAKE);
        assertThat(stage.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stage.getFailureCode()).isEqualTo("ENTRY_CONDITION");
        assertThat(stage.getStartedAt()).isNull();
        assertThat(intake.calls.get()).isZero();
        assertThat(stages(runId).get(UNDERSTAND).getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    void claimIsCommittedBeforeExecutionAndBusyRunIsRejectedImmediately() throws Exception {
        defaultStubs("GREENFIELD", List.of());
        CountDownLatch release = new CountDownLatch(1);
        stubs.put(INTAKE, StubExecutor.blocking(INTAKE, release));
        UUID runId = newRun();

        CompletableFuture<Void> background = CompletableFuture.runAsync(() -> engine().advance(runId));
        try {
            // H2: the RUNNING claim is visible from a separate connection while the stage executes
            long deadline = System.currentTimeMillis() + 5_000;
            String status = null;
            while (System.currentTimeMillis() < deadline) {
                status = jdbc.queryForObject("select status from workflow_stage where run_id = ? and node = 'INTAKE'",
                        String.class, runId);
                if ("RUNNING".equals(status)) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(status).isEqualTo("RUNNING");

            // H4: a second command on the busy run fails fast with 409 CONFLICT
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> engine().advance(runId))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getCategory()).isEqualTo(ErrorCategory.CONFLICT);
                        assertThat(e.getStatus().value()).isEqualTo(409);
                        assertThat(e.getMessage()).contains("run busy");
                    });
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(500);
        } finally {
            release.countDown();
            background.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void storeWritesRequireTheRunLockAndClaimsAreConditional() {
        defaultStubs("GREENFIELD", List.of());
        UUID runId = newRun();

        // H1: a write from a thread that does not hold the run lock is refused
        assertThatThrownBy(() -> store.claimStage(runId, INTAKE)).isInstanceOf(IllegalStateException.class);

        // conditional PENDING->RUNNING: the second claim of the same stage does not succeed
        Boolean[] claims = locks.withLock(runId, () -> new Boolean[] {
                store.claimStage(runId, INTAKE), store.claimStage(runId, INTAKE)});
        assertThat(claims).containsExactly(true, false);
    }

    private Map<Node, WorkflowStage> stages(UUID runId) {
        Map<Node, WorkflowStage> map = new EnumMap<>(Node.class);
        store.loadStages(runId).forEach(st -> map.put(st.getNode(), st));
        return map;
    }

    private static List<StageStatus> statusOf(Map<Node, WorkflowStage> stages, Node... nodes) {
        List<StageStatus> list = new ArrayList<>();
        for (Node node : nodes) {
            list.add(stages.get(node).getStatus());
        }
        return list;
    }

    private List<AuditEventType> eventTypes(UUID runId) {
        return events.findByRunIdOrderBySeqAsc(runId).stream().map(AuditEvent::getType).toList();
    }

    /** Test double: executes stage logic only, never touches persistence. */
    static final class StubExecutor implements StageExecutor {
        final Node node;
        final Function<StageContext, StageResult> behaviour;
        final AtomicInteger calls = new AtomicInteger();
        Function<StageContext, Optional<String>> entryCheck = ctx -> Optional.empty();
        Function<Map<String, Object>, Optional<String>> exitCheck = out -> Optional.empty();

        StubExecutor(Node node, Function<StageContext, StageResult> behaviour) {
            this.node = node;
            this.behaviour = behaviour;
        }

        static StubExecutor succeeding(Node node) {
            return new StubExecutor(node, ctx -> StageResult.success(Map.of("stub", node.name()), Provenance.ACTUAL));
        }

        static StubExecutor sleeping(Node node, long millis) {
            return new StubExecutor(node, ctx -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return StageResult.success(Map.of("stub", node.name()), Provenance.ACTUAL);
            });
        }

        static StubExecutor blocking(Node node, CountDownLatch release) {
            return new StubExecutor(node, ctx -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return StageResult.success(Map.of("stub", node.name()), Provenance.ACTUAL);
            });
        }

        @Override
        public Node node() {
            return node;
        }

        @Override
        public StageResult execute(StageContext context) {
            calls.incrementAndGet();
            return behaviour.apply(context);
        }

        @Override
        public Optional<String> checkEntry(StageContext context) {
            return entryCheck.apply(context);
        }

        @Override
        public Optional<String> checkExit(Map<String, Object> output) {
            return exitCheck.apply(output);
        }
    }
}
