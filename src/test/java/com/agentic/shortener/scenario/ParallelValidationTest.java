package com.agentic.shortener.scenario;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T069 / SC-002 with the REAL TEST, DOCS and SECURITY executors: each is wrapped so that it waits at a
 * three-party barrier before doing its real work. The barrier can only open if all three are executing at the
 * same moment on different threads; sequential execution would time out and fail the stage. The persisted
 * intervals must therefore overlap pairwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ParallelValidationTest.BarrierAtParallelGroup.class)
class ParallelValidationTest {

    private static final Set<Node> GROUP = Set.of(Node.TEST, Node.DOCS, Node.SECURITY);
    private static final CyclicBarrier BARRIER = new CyclicBarrier(GROUP.size());
    private static final AtomicInteger PASSED_BARRIER = new AtomicInteger();

    @TestConfiguration
    static class BarrierAtParallelGroup {
        @Bean
        static BeanPostProcessor barrierWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof StageExecutor e && GROUP.contains(e.node()) ? new AtBarrier(e) : bean;
                }
            };
        }
    }

    record AtBarrier(StageExecutor real) implements StageExecutor {
        @Override
        public Node node() {
            return real.node();
        }

        @Override
        public StageResult execute(StageContext context) {
            try {
                BARRIER.await(5, TimeUnit.SECONDS);
                PASSED_BARRIER.incrementAndGet();
            } catch (Exception e) {
                throw new IllegalStateException("parallel group did not run concurrently", e);
            }
            return real.execute(context);
        }

        @Override
        public Optional<String> checkEntry(StageContext context) {
            return real.checkEntry(context);
        }

        @Override
        public Optional<String> checkExit(Map<String, Object> output) {
            return real.checkExit(output);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void realValidationStagesRunConcurrentlyWithOverlappingPersistedIntervals() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        api.fixtureEvidence(runId).andExpect(status().isOk());

        String run = api.runJson(runId);
        List<Interval> intervals = GROUP.stream().map(n -> {
            assertThat(field(run, n, "status")).isEqualTo("SUCCEEDED");
            return new Interval(n, Instant.parse(field(run, n, "startedAt")), Instant.parse(field(run, n, "endedAt")),
                    field(run, n, "threadName"));
        }).toList();

        // All three passed the barrier together: only possible if they were executing at the same time.
        assertThat(PASSED_BARRIER.get()).isEqualTo(GROUP.size());
        assertThat(intervals).extracting(Interval::thread).doesNotHaveDuplicates();
        for (Interval a : intervals) {
            for (Interval b : intervals) {
                if (a != b) { // the persisted intervals overlap (clock ticks may make start == end)
                    assertThat(a.start()).as("%s starts no later than %s ends", a.node(), b.node())
                            .isBeforeOrEqualTo(b.end());
                }
            }
        }
        assertThat(api.status(runId)).isEqualTo("AWAITING_APPROVAL");
    }

    private static String field(String run, Node node, String name) {
        List<String> values = JsonPath.read(run, "$.stages[?(@.node == '" + node + "')]." + name);
        return values.get(0);
    }

    record Interval(Node node, Instant start, Instant end, String thread) {
    }
}
