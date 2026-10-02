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
final class WorkflowApiClient {

    static final String SCN_A = "Create a short link for a valid HTTP/HTTPS address, redirect to the original address, "
            + "and record redirect count and last redirect time.";

    private final MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    WorkflowApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    ResultActions create(String requirement, String actorType, String actorIdentity) throws Exception {
        return mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requirement", requirement, "actorType", actorType,
                        "actorIdentity", actorIdentity))));
    }

    UUID createRun(String requirement) throws Exception {
        String body = create(requirement, "HUMAN", "candidate").andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    ResultActions fetch(UUID runId, String suffix) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/workflows/" + runId + suffix));
    }

    String runJson(UUID runId) throws Exception {
        return mvc.perform(get("/api/workflows/" + runId)).andReturn().getResponse().getContentAsString();
    }

    ResultActions gate(UUID runId, String action, String gate, String actorType, String actorIdentity, int planVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gate", gate);
        body.put("actorType", actorType);
        body.put("actorIdentity", actorIdentity);
        body.put("reason", "reviewed");
        body.put("planVersion", planVersion);
        return postJson("/api/workflows/" + runId + "/" + action, body);
    }

    ResultActions approve(UUID runId, String gate, int planVersion) throws Exception {
        return gate(runId, "approve", gate, "HUMAN", "candidate", planVersion);
    }

    ResultActions terminate(UUID runId) throws Exception {
        return postJson("/api/workflows/" + runId + "/terminate",
                Map.of("actorType", "HUMAN", "actorIdentity", "candidate", "reason", "no longer needed"));
    }

    ResultActions evidence(UUID runId, Map<String, Object> body) throws Exception {
        return postJson("/api/workflows/" + runId + "/implementation", body);
    }

    ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    List<String> decisionTypes(UUID runId) throws Exception {
        String body = mvc.perform(get("/api/workflows/" + runId + "/decisions")).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    List<String> eventTypes(UUID runId) throws Exception {
        String body = mvc.perform(get("/api/workflows/" + runId + "/events")).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    String status(UUID runId) throws Exception {
        return JsonPath.read(runJson(runId), "$.status");
    }

    String stageStatus(UUID runId, String node) throws Exception {
        List<String> statuses = JsonPath.read(runJson(runId), "$.stages[?(@.node == '" + node + "')].status");
        return statuses.get(0);
    }
}
