package com.agentic.shortener.workflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Thin MockMvc client for the workflow API (contracts/openapi.yaml). Decisions here are test fixtures. */
public final class WorkflowApiClient {

    public static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the original address, "
            + "and record redirect count and last redirect time.";

    private final MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    public WorkflowApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions create(String requirement, String actorType, String actorIdentity) throws Exception {
        return mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requirement", requirement, "actorType", actorType,
                        "actorIdentity", actorIdentity))));
    }

    public UUID createRun(String requirement) throws Exception {
        String body = create(requirement, "HUMAN", "candidate").andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Run creation with a fault plan (FR-REL-011); accepted only when fault injection is enabled. */
    public ResultActions createWithFaults(String requirement, List<Map<String, Object>> faults) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requirement", requirement);
        body.put("actorType", "HUMAN");
        body.put("actorIdentity", "candidate");
        body.put("faults", faults);
        return postJson("/api/workflows", body);
    }

    public UUID createRunWithFaults(String requirement, List<Map<String, Object>> faults) throws Exception {
        String body = createWithFaults(requirement, faults).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public static Map<String, Object> fault(String stage, String type, int times) {
        return Map.of("stage", stage, "type", type, "times", times);
    }

    /** Design approval plus labelled fixture evidence: the run then executes TEST ‖ DOCS ‖ SECURITY. */
    public void throughValidation(UUID runId) throws Exception {
        approve(runId, "DESIGN_APPROVAL", 1);
        fixtureEvidence(runId);
    }

    public ResultActions resume(UUID runId, String actorType, String actorIdentity) throws Exception {
        return postJson("/api/workflows/" + runId + "/resume",
                Map.of("actorType", actorType, "actorIdentity", actorIdentity, "reason", "resume after review"));
    }

    public List<Map<String, Object>> events(UUID runId) throws Exception {
        return JsonPath.read(fetch(runId, "/events").andReturn().getResponse().getContentAsString(), "$[*]");
    }

    public Object stageField(UUID runId, String node, String field) throws Exception {
        List<Object> values = JsonPath.read(runJson(runId), "$.stages[?(@.node == '" + node + "')]." + field);
        return values.get(0);
    }

    public ResultActions fetch(UUID runId, String suffix) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/workflows/" + runId + suffix));
    }

    public String runJson(UUID runId) throws Exception {
        return mvc.perform(get("/api/workflows/" + runId)).andReturn().getResponse().getContentAsString();
    }

    public ResultActions gate(UUID runId, String action, String gate, String actorType, String actorIdentity, int planVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gate", gate);
        body.put("actorType", actorType);
        body.put("actorIdentity", actorIdentity);
        body.put("reason", "reviewed");
        body.put("planVersion", planVersion);
        return postJson("/api/workflows/" + runId + "/" + action, body);
    }

    public ResultActions approve(UUID runId, String gate, int planVersion) throws Exception {
        return gate(runId, "approve", gate, "HUMAN", "candidate", planVersion);
    }

    /** RELEASE_APPROVAL with explicitly accepted residual risks (test fixture decision). */
    public ResultActions approveRelease(UUID runId, int planVersion, List<String> acceptedRisks) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gate", "RELEASE_APPROVAL");
        body.put("actorType", "HUMAN");
        body.put("actorIdentity", "candidate");
        body.put("reason", "reviewed readiness report (test fixture)");
        body.put("planVersion", planVersion);
        body.put("acceptedRisks", acceptedRisks);
        return postJson("/api/workflows/" + runId + "/approve", body);
    }

    /** Implementation evidence citing the run's own requirement IDs (labelled test fixture). */
    public ResultActions fixtureEvidence(UUID runId) throws Exception {
        List<String> scope = JsonPath.read(runJson(runId), "$.stages[?(@.node == 'DESIGN')].output.requirementIds[*]");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "AGENT");
        body.put("actorIdentity", "claude-code");
        body.put("planVersion", 1);
        body.put("summary", "TEST FIXTURE evidence: link components exist in this build");
        body.put("changedArtifacts", List.of("src/main/java/com/agentic/shortener/link/LinkService.java",
                "src/main/java/com/agentic/shortener/link/LinkController.java"));
        body.put("revision", "0000000");
        body.put("requirementIds", scope);
        return evidence(runId, body);
    }

    public ResultActions terminate(UUID runId) throws Exception {
        return postJson("/api/workflows/" + runId + "/terminate",
                Map.of("actorType", "HUMAN", "actorIdentity", "candidate", "reason", "no longer needed"));
    }

    public ResultActions evidence(UUID runId, Map<String, Object> body) throws Exception {
        return postJson("/api/workflows/" + runId + "/implementation", body);
    }

    public ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    public List<String> decisionTypes(UUID runId) throws Exception {
        String body = mvc.perform(get("/api/workflows/" + runId + "/decisions")).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    public List<String> eventTypes(UUID runId) throws Exception {
        String body = mvc.perform(get("/api/workflows/" + runId + "/events")).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    public String status(UUID runId) throws Exception {
        return JsonPath.read(runJson(runId), "$.status");
    }

    public String stageStatus(UUID runId, String node) throws Exception {
        List<String> statuses = JsonPath.read(runJson(runId), "$.stages[?(@.node == '" + node + "')].status");
        return statuses.get(0);
    }
}
