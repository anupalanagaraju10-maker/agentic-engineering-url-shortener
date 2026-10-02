package com.agentic.shortener.workflow;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T086 (FR-ORC-012, CHK015 / research R20): the same requirement, HUMAN decisions and fault plan produce the
 * same semantic stage path. Timestamps, ids, thread names, short codes, probe ids and the order of events
 * inside one parallel wave are ignored; per-node event order is compared.
 */
@SpringBootTest(properties = "workflow.fault-injection.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeterminismTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void sameInputsGiveTheSameSemanticPath() throws Exception {
        Map<String, Object> first = semanticPath(runOnce());
        Map<String, Object> second = semanticPath(runOnce());

        assertThat(second).isEqualTo(first);
        assertThat(first.get("finalStatus")).isEqualTo("COMPLETED");
    }

    private UUID runOnce() throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRunWithFaults(SCN_A, List.of(fault("DOCS", "TRANSIENT", 1), fault("TEST", "TRANSIENT", 1)));
        api.throughValidation(runId);
        api.approveRelease(runId, 1, List.of("fixture"));
        return runId;
    }

    private Map<String, Object> semanticPath(UUID runId) throws Exception {
        WorkflowApiClient api = new WorkflowApiClient(mvc);
        String run = api.runJson(runId);
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("finalStatus", JsonPath.read(run, "$.status"));
        path.put("changeType", JsonPath.read(run, "$.changeType"));
        List<Map<String, Object>> stages = JsonPath.read(run, "$.stages[*]");
        Map<String, String> stageOutcome = new TreeMap<>();
        for (Map<String, Object> s : stages) {
            stageOutcome.put((String) s.get("node"), s.get("status") + "/" + s.get("provenance") + "/" + s.get("attempts")
                    + "/" + s.get("failureClass"));
        }
        path.put("stages", stageOutcome);
        path.put("understand", pick(JsonPath.read(run, "$.stages[?(@.node == 'UNDERSTAND')].output"),
                "capabilities", "findings", "changeType"));
        path.put("design", pick(JsonPath.read(run, "$.stages[?(@.node == 'DESIGN')].output"),
                "requirementIds", "implementationRequired", "securitySensitive", "components"));
        List<String> policies = new ArrayList<>();
        for (Map<String, Object> p : JsonPath.<List<Map<String, Object>>>read(run, "$.policyEvaluations[*]")) {
            policies.add(p.get("checkId") + "@" + p.get("node") + "=" + p.get("result"));
        }
        path.put("policies", policies);
        List<String> decisions = new ArrayList<>();
        for (Map<String, Object> d : JsonPath.<List<Map<String, Object>>>read(
                api.fetch(runId, "/decisions").andReturn().getResponse().getContentAsString(), "$[*]")) {
            decisions.add(d.get("type") + ":" + d.get("gate") + ":" + d.get("actorType") + ":" + d.get("payload"));
        }
        path.put("decisions", decisions.stream().map(s -> s.replaceAll("(?i)decisionId=\\d+", "decisionId")).toList());
        Map<String, List<String>> perNode = new TreeMap<>();
        List<String> runLevel = new ArrayList<>();
        for (Map<String, Object> e : api.events(runId)) {
            Object node = e.get("node");
            String label = e.get("type") + (Boolean.TRUE.equals(e.get("injected")) ? "*" : "");
            if (node == null) {
                runLevel.add(label);
            } else {
                perNode.computeIfAbsent((String) node, n -> new ArrayList<>()).add(label);
            }
        }
        path.put("eventsPerNode", perNode);
        path.put("runLevelEvents", runLevel);
        return path;
    }

    private static Map<String, Object> pick(List<Map<String, Object>> outputs, String... keys) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String key : keys) {
            picked.put(key, outputs.get(0).get(key));
        }
        return picked;
    }
}
