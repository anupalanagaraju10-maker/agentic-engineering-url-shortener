package com.agentic.shortener.workflow.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.engine.Actor;
import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.AuditEvent;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** T014: append-only, sequenced audit trail (FR-OBS-001/002, H1). */
@SpringBootTest
@ActiveProfiles("test")
class AuditServiceTest {

    private static final Actor CANDIDATE = new Actor(ActorType.HUMAN, "candidate");

    @Autowired
    private WorkflowStore store;
    @Autowired
    private AuditService audit;
    @Autowired
    private AuditEventRepository events;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void eventsCarryRunContextAndIncreasingSeq() {
        UUID runId = store.createRun("Create a short link", CANDIDATE, "corr-123");

        audit.append(runId, AuditEventType.STAGE_STARTED, Node.INTAKE, Actor.ENGINE, Map.of("attempt", 1), false);
        audit.append(runId, AuditEventType.STAGE_SUCCEEDED, Node.INTAKE, Actor.ENGINE, Map.of(), true);

        List<AuditEvent> trail = events.findByRunIdOrderBySeqAsc(runId);
        assertThat(trail).extracting(AuditEvent::getSeq).containsExactly(1, 2, 3); // RUN_CREATED is seq 1
        AuditEvent last = trail.get(2);
        assertThat(last.getRunId()).isEqualTo(runId);
        assertThat(last.getCorrelationId()).isEqualTo("corr-123");
        assertThat(last.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(last.getActorIdentity()).isEqualTo("workflow-engine");
        assertThat(last.getPlanVersion()).isEqualTo(1);
        assertThat(last.getPolicyVersion()).isEqualTo("v1");
        assertThat(last.isInjected()).isTrue();
        assertThat(last.getCreatedAt()).isNotNull();
        assertThat(trail.get(0).getType()).isEqualTo(AuditEventType.RUN_CREATED);
        assertThat(trail.get(0).getActorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void concurrentAppendsProduceUniqueGapFreeSeq() throws Exception {
        UUID runId = store.createRun("Create a short link", CANDIDATE, null);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Callable<Void>> work = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            work.add(() -> {
                for (int i = 0; i < 20; i++) {
                    audit.append(runId, AuditEventType.STAGE_STARTED, Node.TEST, Actor.ENGINE, Map.of(), false);
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(work)) {
            f.get(); // propagates any UNIQUE(run_id, seq) violation
        }
        pool.shutdown();

        assertThat(events.findByRunIdOrderBySeqAsc(runId)).extracting(AuditEvent::getSeq)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 61).boxed().toList());
    }

    @Test
    void repositoryOffersOnlyAnAppendWritePath() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditEventRepository.class)).isFalse();
        for (Method m : AuditEventRepository.class.getMethods()) {
            assertThat(m.getName()).as("repository method").doesNotStartWith("delete").doesNotStartWith("update");
            assertThat(m.isAnnotationPresent(Modifying.class)).as("@Modifying on %s", m.getName()).isFalse();
        }
    }

    @Test
    void entityIsImmutable() {
        assertThat(AuditEvent.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(Arrays.stream(AuditEvent.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void anyHttpRouteOverEventsIsReadOnly() {
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            boolean eventsRoute = info.getPatternValues().stream().anyMatch(p -> p.contains("events"));
            if (eventsRoute) {
                assertThat(info.getMethodsCondition().getMethods()).containsExactly(RequestMethod.GET);
            }
        });
    }
}
