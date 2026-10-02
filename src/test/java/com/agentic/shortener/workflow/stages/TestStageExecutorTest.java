package com.agentic.shortener.workflow.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.engine.CancellationToken;
import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * T065: TEST runs the real acceptance probes of every task through {@link LinkService} (FR-ORC-014). Probe
 * links are tagged with the run id and removed on success (on failure the engine compensates); a failing probe is a PERMANENT
 * IMPLEMENTATION_DEFECT; a revoked attempt creates no probe link (H3).
 */
@SpringBootTest
@ActiveProfiles("test")
class TestStageExecutorTest {

    @MockitoSpyBean
    private LinkService links;

    @Autowired
    private TestStageExecutor executor;

    private final CapabilityRegistry registry = new CapabilityRegistry();

    @Test
    void runsEveryAcceptanceProbeOfEveryTaskAndCleansUp() {
        StageContext context = context(Capability.CREATE_LINK, Capability.REDIRECT, Capability.ANALYTICS,
                Capability.IDEMPOTENCY);

        StageResult result = executor.execute(context);

        Map<String, Object> output = StageTestSupport.outputOf(result);
        assertThat(result.provenance()).isEqualTo(Provenance.ACTUAL);
        List<Map<String, Object>> probes = probes(output);
        assertThat(probes).extracting(p -> p.get("id")).containsExactly("probe.create-link",
                "probe.reject-unsafe-url", "probe.redirect", "probe.unknown-code", "probe.redirect-count",
                "probe.idempotent-replay", "probe.idempotent-conflict");
        assertThat(probes).allSatisfy(p -> {
            assertThat(p.get("passed")).isEqualTo(true);
            assertThat(String.valueOf(p.get("detail"))).isNotBlank();
        });
        assertThat((Integer) output.get("probeLinksCreated")).isPositive();
        assertThat(output.get("probeLinksRemaining")).isEqualTo(0);
        assertThat(links.countProbeLinks(context.runId())).isZero();
        assertThat(executor.checkExit(output)).isEmpty();
    }

    @Test
    void aFailingProbeIsAPermanentImplementationDefectAndLeavesProbesForCompensation() {
        doReturn("https://wrong.example.com/").when(links).redirect(anyString());
        StageContext context = context(Capability.CREATE_LINK, Capability.REDIRECT);

        StageResult result = executor.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(result.failureCode()).isEqualTo("IMPLEMENTATION_DEFECT");
        assertThat(result.failureReason()).contains("probe.redirect");
        // Phase 6 (ADR-0005 §6): a failed attempt leaves its probe links to the engine's compensation sweep
        assertThat(links.countProbeLinks(context.runId())).isPositive();
        links.deleteProbeLinks(context.runId());
    }

    @Test
    void aCapabilityWithoutAnImplementedProbeIsADefect() {
        StageContext context = context(Capability.EXPIRATION); // still PLANNED: no probe exists in this build

        StageResult result = executor.execute(context);

        assertThat(result.failureCode()).isEqualTo("IMPLEMENTATION_DEFECT");
        assertThat(result.failureReason()).contains("probe.expired-link");
    }

    @Test
    void aRevokedAttemptCreatesNoProbeLink() {
        CancellationToken revoked = new CancellationToken();
        revoked.revoke();
        StageContext context = new StageContext(UUID.randomUUID(), "fixture", 1, upstream(Capability.CREATE_LINK),
                revoked);

        StageResult result = executor.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(result.failureCode()).isEqualTo("CANCELLED");
        verify(links, never()).createProbeLink(anyString(), any(), any());
        assertThat(links.countProbeLinks(context.runId())).isZero();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> probes(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("probes");
    }

    private StageContext context(Capability... capabilities) {
        return new StageContext(UUID.randomUUID(), "fixture", 1, upstream(capabilities), new CancellationToken());
    }

    private Map<Node, Map<String, Object>> upstream(Capability... capabilities) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        int n = 1;
        for (Capability capability : capabilities) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", "TASK-" + n++);
            task.put("capability", capability.name());
            task.put("acceptanceChecks", registry.entry(capability).probeIds());
            tasks.add(task);
        }
        Map<Node, Map<String, Object>> upstream = new EnumMap<>(Node.class);
        upstream.put(Node.DECOMPOSE, Map.of("tasks", tasks));
        return upstream;
    }
}
