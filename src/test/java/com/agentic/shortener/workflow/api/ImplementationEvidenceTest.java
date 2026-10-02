package com.agentic.shortener.workflow.api;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.engine.DecisionType;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.DecisionRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T036: implementation evidence for the EXTERNAL_ACTION (ADR-0004 §3, CHK004, CHK024, FR-ORC-014). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImplementationEvidenceTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DecisionRepository decisions;

    private WorkflowApiClient api;

    @BeforeEach
    void setUp() {
        api = new WorkflowApiClient(mvc);
    }

    private UUID awaitingImplementation() throws Exception {
        UUID runId = api.createRun(SCN_A);
        api.approve(runId, "DESIGN_APPROVAL", 1).andExpect(status().isOk());
        return runId;
    }

    private static Map<String, Object> evidence(String actorType, String actorIdentity) {
        Map<String, Object> body = new HashMap<>();
        body.put("actorType", actorType);
        body.put("actorIdentity", actorIdentity);
        body.put("planVersion", 1);
        body.put("summary", "Implemented create, redirect and analytics (test fixture)");
        body.put("changedArtifacts", List.of("src/main/java/com/agentic/shortener/link/LinkService.java"));
        body.put("revision", "abc1234");
        body.put("requirementIds", List.of("FR-URL-001", "FR-URL-006"));
        return body;
    }

    @Test
    void evidenceBeforeDesignApprovalIsRefused() throws Exception {
        UUID runId = api.createRun(SCN_A); // still AWAITING_APPROVAL
        api.evidence(runId, evidence("HUMAN", "candidate")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("INVALID_STATE"));
        assertThat(api.decisionTypes(runId)).contains("DECISION_REFUSED");
    }

    @Test
    void stalePlanVersionAndSystemActorAreRefused() throws Exception {
        UUID runId = awaitingImplementation();
        Map<String, Object> stale = evidence("HUMAN", "candidate");
        stale.put("planVersion", 2);
        api.evidence(runId, stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("STALE_PLAN_VERSION"));
        api.evidence(runId, evidence("SYSTEM", "workflow-engine")).andExpect(status().isBadRequest());
    }

    @Test
    void structuralRulesAreEnforced() throws Exception {
        UUID runId = awaitingImplementation();

        Map<String, Object> noRevision = evidence("HUMAN", "candidate");
        noRevision.remove("revision");
        api.evidence(runId, noRevision).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));

        Map<String, Object> badRevision = evidence("HUMAN", "candidate");
        badRevision.put("revision", "not-a-sha");
        api.evidence(runId, badRevision).andExpect(status().isBadRequest());

        Map<String, Object> noChangeNoJustification = evidence("HUMAN", "candidate");
        noChangeNoJustification.put("changedArtifacts", List.of());
        noChangeNoJustification.remove("revision");
        api.evidence(runId, noChangeNoJustification).andExpect(status().isBadRequest());

        Map<String, Object> justificationButImplementationRequired = new HashMap<>(noChangeNoJustification);
        justificationButImplementationRequired.put("noChangeJustification", "already implemented");
        api.evidence(runId, justificationButImplementationRequired).andExpect(status().isBadRequest());

        Map<String, Object> noRequirementIds = evidence("HUMAN", "candidate");
        noRequirementIds.put("requirementIds", List.of());
        api.evidence(runId, noRequirementIds).andExpect(status().isBadRequest());

        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
    }

    @Test
    void requirementIdOutsideTheRunIsAScopeMismatch() throws Exception {
        UUID runId = awaitingImplementation();
        Map<String, Object> body = evidence("HUMAN", "candidate");
        body.put("requirementIds", List.of("FR-URL-001", "FR-URL-008")); // FR-URL-008 is not in SCN-A
        api.evidence(runId, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("EVIDENCE_SCOPE_MISMATCH"));
        assertThat(api.decisionTypes(runId)).contains("DECISION_REFUSED");
        assertThat(api.status(runId)).isEqualTo("AWAITING_IMPLEMENTATION");
    }

    @Test
    void validAgentEvidenceCompletesTheExternalAction() throws Exception {
        UUID runId = awaitingImplementation();

        api.evidence(runId, evidence("AGENT", "claude-code")).andExpect(status().isOk());

        String run = api.runJson(runId);
        assertThat(api.stageStatus(runId, "IMPLEMENT")).isEqualTo("SUCCEEDED");
        List<String> provenance = JsonPath.read(run, "$.stages[?(@.node == 'IMPLEMENT')].provenance");
        assertThat(provenance).containsExactly("EXTERNAL");
        List<Map<String, Object>> output = JsonPath.read(run, "$.stages[?(@.node == 'IMPLEMENT')].output");
        assertThat(output.get(0)).containsKeys("coveredComponents", "uncoveredComponents", "revision");

        List<Decision> lineage = decisions.findByRunIdOrderByIdAsc(runId);
        Decision approval = lineage.stream().filter(d -> d.getType() == DecisionType.APPROVAL).findFirst().orElseThrow();
        Decision evidence = lineage.stream().filter(d -> d.getType() == DecisionType.IMPLEMENTATION_EVIDENCE)
                .findFirst().orElseThrow();
        assertThat(evidence.getActorIdentity()).isEqualTo("claude-code");
        assertThat(evidence.getCreatedAt()).isAfter(approval.getCreatedAt());
        assertThat(api.eventTypes(runId)).contains("IMPLEMENTATION_RECORDED");
    }
}
