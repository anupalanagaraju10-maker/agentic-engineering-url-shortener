package com.agentic.shortener.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.ShortenerApplication;
import com.agentic.shortener.workflow.engine.Actor;
import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.DecisionService;
import com.agentic.shortener.workflow.engine.DecisionService.GateCommand;
import com.agentic.shortener.workflow.engine.Fault;
import com.agentic.shortener.workflow.engine.FaultType;
import com.agentic.shortener.workflow.engine.ImplementationEvidenceService;
import com.agentic.shortener.workflow.engine.ImplementationEvidenceService.EvidenceCommand;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.RunStatus;
import com.agentic.shortener.workflow.engine.StageStatus;
import com.agentic.shortener.workflow.engine.WorkflowEngine;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowStage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T084 (FR-ORC-006, NFR-004, SC-005, CHK033, H2): two application contexts over one H2 file. Waiting runs
 * are identical after restart and continue normally. A crash image taken while a stage was executing (H2
 * online BACKUP of the live file) is recovered at startup: attempt rolled back, compensation sweep, run
 * SAFE_STOPPED (INTERRUPTED, recoverable), and nothing is re-executed until a HUMAN resumes.
 */
class RestartPersistenceTest {

    private static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the "
            + "original address, and record redirect count and last redirect time.";
    private static final Actor CANDIDATE = new Actor(ActorType.HUMAN, "candidate");

    @TempDir
    Path dir;

    @Test
    void waitingRunsSurviveRestartAndInterruptedRunsAreSafeStopped() throws Exception {
        Path crashImage = dir.resolve("crash.zip");
        UUID atGate;
        UUID atImplementation;
        UUID atBoundary;
        UUID midStage;
        Map<UUID, Snapshot> before;

        try (ConfigurableApplicationContext first = start(dir.resolve("live"))) {
            WorkflowEngine engine = first.getBean(WorkflowEngine.class);
            DecisionService decisions = first.getBean(DecisionService.class);
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);

            atGate = newRun(engine, List.of());
            atImplementation = newRun(engine, List.of());
            decisions.approve(atImplementation, new GateCommand(CANDIDATE, "approved", "DESIGN_APPROVAL", 1, List.of()));
            atBoundary = newRun(engine, List.of());
            // interrupted between a committed decision and advancement: RUNNING with no stage RUNNING
            jdbc.update("update workflow_run set status = 'RUNNING', pending_action = null where id = ?", atBoundary);

            before = Map.of(atGate, snapshot(first, atGate), atImplementation, snapshot(first, atImplementation));

            UUID created = engine.createRun(SCN_A, CANDIDATE, null,
                    List.of(new Fault(Node.UNDERSTAND, FaultType.DELAY, 1, 30_000)));
            midStage = created;
            CompletableFuture.runAsync(() -> engine.advance(created));
            // H2: the claim is committed in its own transaction, so another connection sees UNDERSTAND RUNNING
            waitFor(() -> "RUNNING".equals(jdbc.queryForObject(
                    "select status from workflow_stage where run_id = ? and node = 'UNDERSTAND'", String.class, created)));
            jdbc.execute("BACKUP TO '" + crashImage.toString().replace('\\', '/') + "'");
        }

        Path restored = dir.resolve("restored");
        unzip(crashImage, restored);
        try (ConfigurableApplicationContext second = start(restored)) {
            WorkflowStore store = second.getBean(WorkflowStore.class);

            assertThat(snapshot(second, atGate)).isEqualTo(before.get(atGate));
            assertThat(snapshot(second, atImplementation)).isEqualTo(before.get(atImplementation));

            WorkflowRun crashed = store.loadRun(midStage);
            assertThat(crashed.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(crashed.getRecoverable()).isTrue();
            assertThat(crashed.getStopReason()).contains("INTERRUPTED");
            WorkflowStage understand = store.stageMap(midStage).get(Node.UNDERSTAND);
            assertThat(understand.getStatus()).isEqualTo(StageStatus.PENDING); // attempt rolled back
            assertThat(understand.getAttempts()).isEqualTo(1);                 // and not re-executed
            assertThat(store.stageMap(midStage).get(Node.DECOMPOSE).getStatus()).isEqualTo(StageStatus.PENDING);
            assertThat(types(second, midStage)).containsSubsequence("STAGE_STARTED", "ATTEMPT_ROLLED_BACK",
                    "FAILURE_DETECTED", "SAFE_STOPPED");

            WorkflowRun boundary = store.loadRun(atBoundary);
            assertThat(boundary.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(boundary.getRecoverable()).isTrue();
            assertThat(boundary.getStopReason()).contains("INTERRUPTED");

            // everything continues normally after restart
            DecisionService decisions = second.getBean(DecisionService.class);
            decisions.approve(atGate, new GateCommand(CANDIDATE, "approved after restart", "DESIGN_APPROVAL", 1, List.of()));
            assertThat(store.loadRun(atGate).getStatus()).isEqualTo(RunStatus.AWAITING_IMPLEMENTATION);

            @SuppressWarnings("unchecked")
            List<String> scope = (List<String>) store.outputs(atImplementation).get(Node.DESIGN).get("requirementIds");
            second.getBean(ImplementationEvidenceService.class).record(atImplementation, new EvidenceCommand(
                    new Actor(ActorType.AGENT, "claude-code"), 1, "TEST FIXTURE evidence after restart",
                    List.of("src/main/java/com/agentic/shortener/link/LinkService.java"), "0000000", null, scope));
            assertThat(store.loadRun(atImplementation).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);

            decisions.resume(midStage, CANDIDATE, "resume after restart");
            assertThat(store.loadRun(midStage).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
            assertThat(store.stageMap(midStage).get(Node.UNDERSTAND).getAttempts()).isEqualTo(2);
        }
    }

    private static UUID newRun(WorkflowEngine engine, List<Fault> faults) {
        UUID runId = engine.createRun(SCN_A, CANDIDATE, null, faults);
        engine.advance(runId);
        return runId;
    }

    private record Snapshot(RunStatus status, String pendingAction, int planVersion, List<String> stages,
            List<String> events) {
    }

    private static Snapshot snapshot(ConfigurableApplicationContext context, UUID runId) {
        WorkflowStore store = context.getBean(WorkflowStore.class);
        WorkflowRun run = store.loadRun(runId);
        List<String> stages = new ArrayList<>();
        store.loadStages(runId).forEach(s -> stages.add(s.getNode() + "=" + s.getStatus() + "/" + s.getAttempts()));
        stages.sort(String::compareTo);
        List<String> events = context.getBean(AuditEventRepository.class).findByRunIdOrderBySeqAsc(runId).stream()
                .map(e -> e.getSeq() + ":" + e.getType() + ":" + e.getNode()).toList();
        return new Snapshot(run.getStatus(), run.getPendingAction(), run.getPlanVersion(), stages, events);
    }

    private static List<String> types(ConfigurableApplicationContext context, UUID runId) {
        return context.getBean(AuditEventRepository.class).findByRunIdOrderBySeqAsc(runId).stream()
                .map(AuditEvent::getType).map(Enum::name).toList();
    }

    private static ConfigurableApplicationContext start(Path dataDir) {
        String url = "jdbc:h2:file:" + dataDir.resolve("shortener").toAbsolutePath().toString().replace('\\', '/');
        return new SpringApplicationBuilder(ShortenerApplication.class).profiles("test")
                .run("--spring.datasource.url=" + url, "--spring.main.web-application-type=none",
                        "--workflow.fault-injection.enabled=true", "--workflow.stage-timeout=60s",
                        "--spring.main.banner-mode=off");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(20);
        }
    }

    private static void unzip(Path zip, Path target) throws Exception {
        Files.createDirectories(target);
        try (InputStream in = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(in)) {
            for (ZipEntry e = entries.getNextEntry(); e != null; e = entries.getNextEntry()) {
                Files.copy(entries, target.resolve(Path.of(e.getName()).getFileName()));
            }
        }
    }
}
