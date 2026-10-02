package com.agentic.shortener.contract;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * T135 (reference-alignment review F4; FR-ORC-015, NFR-007): executable validation of
 * {@code contracts/openapi.yaml}. Every documented path and method is served and every application endpoint is
 * documented; the field names of the real responses equal the schema properties. SnakeYAML is already on the
 * classpath through spring-boot-starter (no new dependency, DEP-01 unaffected).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("specs/001-agentic-sdlc-url-shortener/contracts/openapi.yaml");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @Autowired
    private MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyDocumentedOperationIsServedAndEveryEndpointIsDocumented() throws Exception {
        Set<String> documented = new TreeSet<>();
        paths().forEach((path, operations) -> ((Map<?, ?>) operations).keySet()
                .forEach(method -> documented.add(method.toString().toUpperCase(Locale.ROOT) + " " + path)));

        Set<String> served = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().startsWith("com.agentic.shortener")) {
                continue; // framework handlers such as the error controller
            }
            for (String pattern : e.getKey().getPatternValues()) {
                e.getKey().getMethodsCondition().getMethods().forEach(m -> served.add(m.name() + " " + pattern));
            }
        }

        assertThat(documented).as("operations parsed from the contract").hasSize(19); // guard against vacuous pass
        assertThat(served).as("operations served by the application").hasSize(18);  // all but actuator health
        Set<String> actuator = new TreeSet<>(documented.stream().filter(op -> op.contains("/actuator/")).toList());
        Set<String> expected = new TreeSet<>(documented);
        expected.removeAll(actuator);
        assertThat(served).as("application endpoints vs contracts/openapi.yaml").isEqualTo(expected);
        assertThat(actuator).containsExactly("GET /actuator/health");
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void responseFieldsMatchTheSchemas() throws Exception {
        String link = mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/contract\"}")).andReturn().getResponse().getContentAsString();
        assertThat(keys(json.readValue(link, MAP))).as("Link").isEqualTo(properties("Link"));

        WorkflowApiClient api = new WorkflowApiClient(mvc);
        UUID runId = api.createRun(SCN_A);
        Map<String, Object> run = json.readValue(api.runJson(runId), MAP);
        assertThat(keys(run)).as("Run").isEqualTo(properties("Run"));
        assertThat(keys(first(run.get("stages")))).as("Stage").isEqualTo(properties("Stage"));
        assertThat(keys(first(run.get("policyEvaluations")))).as("PolicyEvaluation")
                .isEqualTo(properties("PolicyEvaluation"));
        assertThat(keys(first(api.events(runId)))).as("AuditEvent").isEqualTo(properties("AuditEvent"));
        List<Map<String, Object>> decisions = json.readValue(api.fetch(runId, "/decisions").andReturn().getResponse()
                .getContentAsString(), new TypeReference<>() {
                });
        assertThat(keys(first(decisions))).as("Decision").isEqualTo(properties("Decision"));

        String metrics = mvc.perform(get("/api/metrics/workflows")).andReturn().getResponse().getContentAsString();
        assertThat(keys(json.readValue(metrics, MAP))).as("Metrics").isEqualTo(properties("Metrics"));
    }

    @Test
    void errorsCarryEveryRequiredProblemField() throws Exception {
        String problem = mvc.perform(get("/api/links/zzzzzzz")).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema("Problem").get("required");
        assertThat(keys(json.readValue(problem, MAP))).containsAll(required);
    }

    private static Map<String, Object> paths() throws Exception {
        return castMap(contract().get("paths"));
    }

    private static Set<String> properties(String schema) throws Exception {
        return new TreeSet<>(castMap(schema(schema).get("properties")).keySet());
    }

    private static Map<String, Object> schema(String name) throws Exception {
        return castMap(castMap(castMap(contract().get("components")).get("schemas")).get(name));
    }

    private static Map<String, Object> contract() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT)) {
            return new Yaml().load(in);
        }
    }

    private static Set<String> keys(Map<String, Object> object) {
        return new TreeSet<>(object.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Object list) {
        return ((List<Map<String, Object>>) list).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
