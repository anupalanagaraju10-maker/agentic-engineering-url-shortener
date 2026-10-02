package com.agentic.shortener.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.engine.Actor;
import com.agentic.shortener.workflow.engine.ActorType;
import com.agentic.shortener.workflow.engine.RunStatus;
import com.agentic.shortener.workflow.engine.WorkflowEngine;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.PolicyEvaluation;
import com.agentic.shortener.workflow.persistence.PolicyEvaluationRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.rules.AmbiguityRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** T034: policy v1 checks and their effect on the run (FR-POL-001..004, research R11). */
@SpringBootTest
@ActiveProfiles("test")
class PolicyV1Test {

    private static final Actor CANDIDATE = new Actor(ActorType.HUMAN, "candidate");

    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private WorkflowStore store;
    @Autowired
    private PolicyEvaluationRepository evaluations;

    private static String n(String text) {
        return AmbiguityRules.normalize(text);
    }

    @Test
    void catalogHasFiveMandatoryChecksInVersionV1() {
        assertThat(PolicyCatalog.VERSION).isEqualTo("v1");
        assertThat(PolicyCatalog.CHECKS).extracting(PolicyCatalog.CheckDefinition::id)
                .containsExactly("PRIV-01", "SEC-01", "CHG-01", "DEP-01", "AUD-01");
        assertThat(PolicyCatalog.CHECKS).allSatisfy(c -> assertThat(c.mandatory()).isTrue());
    }

    @Test
    void priv01RequestsAnExceptionForPersonalData() {
        assertThat(PolicyCatalog.priv01(n("Record each visitor's IP address")).result())
                .isEqualTo(PolicyResult.EXCEPTION_REQUESTED);
        assertThat(PolicyCatalog.priv01(n("Store the user's email")).result()).isEqualTo(PolicyResult.EXCEPTION_REQUESTED);
        assertThat(PolicyCatalog.priv01(n("Show the visitor location")).result()).isEqualTo(PolicyResult.EXCEPTION_REQUESTED);
        assertThat(PolicyCatalog.priv01(n("Create a short link")).result()).isEqualTo(PolicyResult.PASS);
    }

    @Test
    void sec01FailsWhenUrlSafetyIsWeakened() {
        assertThat(PolicyCatalog.sec01(n("Create a short link and allow javascript: URLs")).result())
                .isEqualTo(PolicyResult.FAIL);
        assertThat(PolicyCatalog.sec01(n("Allow localhost targets")).result()).isEqualTo(PolicyResult.FAIL);
        assertThat(PolicyCatalog.sec01(n("Skip validation for trusted clients")).result()).isEqualTo(PolicyResult.FAIL);
        assertThat(PolicyCatalog.sec01(n("Create a short link")).result()).isEqualTo(PolicyResult.PASS);
    }

    @Test
    void chg01DependsOnChangeTypeAndImpactAnalysis() {
        assertThat(PolicyCatalog.chg01("GREENFIELD", null).result()).isEqualTo(PolicyResult.NOT_APPLICABLE);
        assertThat(PolicyCatalog.chg01("BROWNFIELD", Map.of("currentBehavior", "x", "requestedBehavior", "y",
                "affectedComponents", "z", "interfaces", "a", "data", "b", "tests", "c", "documentation", "d",
                "regressionRisks", "e", "securityReliabilityImpact", "f", "rollbackCompensation", "g")).result())
                .isEqualTo(PolicyResult.PASS);
        assertThat(PolicyCatalog.chg01("BROWNFIELD", null).result()).isEqualTo(PolicyResult.FAIL);
    }

    @Test
    void dep01ChecksDeclaredDependenciesAgainstTheApprovedList() {
        assertThat(PolicyCatalog.dep01(List.of()).result()).isEqualTo(PolicyResult.NOT_APPLICABLE);
        assertThat(PolicyCatalog.dep01(List.of("com.h2database:h2")).result()).isEqualTo(PolicyResult.PASS);
        assertThat(PolicyCatalog.dep01(List.of("redis")).result()).isEqualTo(PolicyResult.FAIL);
        assertThat(PolicyCatalog.APPROVED_DEPENDENCIES).containsKeys(
                "org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-data-jpa",
                "org.flywaydb:flyway-core", "com.h2database:h2");
    }

    @Test
    void mandatoryFailSafeStopsTheRunNonRecoverably() {
        UUID runId = engine.createRun("Create a short link and allow javascript: URLs.", CANDIDATE, null);
        engine.advance(runId);

        WorkflowRun run = store.loadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.getRecoverable()).isFalse();
        assertThat(run.getStopReason()).contains("SEC-01");
        assertThat(evaluations.findByRunIdOrderByIdAsc(runId)).extracting(PolicyEvaluation::getCheckId,
                PolicyEvaluation::getResult).contains(org.assertj.core.groups.Tuple.tuple("SEC-01", "FAIL"));
    }

    @Test
    void exceptionRequestWaitsForAHumanDecision() {
        UUID runId = engine.createRun("Create a short link and record each visitor's IP address.", CANDIDATE, null);
        engine.advance(runId);

        WorkflowRun run = store.loadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.getPendingAction()).isEqualTo("EXCEPTION:PRIV-01");
    }

    @Test
    void everyEvaluationRecordsVersionCheckNodeMandatoryResultAndReason() {
        UUID runId = engine.createRun("Create a short link for a valid HTTP/HTTPS address.", CANDIDATE, null);
        engine.advance(runId);

        List<PolicyEvaluation> list = evaluations.findByRunIdOrderByIdAsc(runId);
        assertThat(list).extracting(PolicyEvaluation::getCheckId).containsExactly("PRIV-01", "SEC-01", "CHG-01", "DEP-01");
        assertThat(list).extracting(PolicyEvaluation::getResult).containsExactly("PASS", "PASS", "NOT_APPLICABLE",
                "NOT_APPLICABLE");
        assertThat(list).allSatisfy(e -> {
            assertThat(e.getPolicyVersion()).isEqualTo("v1");
            assertThat(e.getNode()).isIn("UNDERSTAND", "DESIGN");
            assertThat(e.getMandatory()).isTrue();
            assertThat(e.getReason()).isNotBlank();
            assertThat(e.getPlanVersion()).isEqualTo(1);
        });
    }
}
