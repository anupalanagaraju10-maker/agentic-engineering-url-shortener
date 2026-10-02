package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** T052: link API per contracts/openapi.yaml (FR-URL-001, 006, 007, 010, 011, 015; research R13/R14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void createReturns201WithTheLink() throws Exception {
        create("https://example.com/docs", null)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(matchesPattern("^[0-9A-Za-z]{7}$")))
                .andExpect(jsonPath("$.shortUrl").value(matchesPattern("^http://localhost/r/[0-9A-Za-z]{7}$")))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/docs"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.redirectCount").value(0))
                .andExpect(jsonPath("$.lastRedirectAt").value(nullValue()));
    }

    @Test
    void unsafeAddressIs400ValidationAndCreatesNothing() throws Exception {
        create("javascript:alert(1)", null)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.category").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value(matchesPattern(".*scheme.*")))
                .andExpect(jsonPath("$.code").doesNotExist());
        create("http://127.0.0.1/admin", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void missingOrMalformedBodyIs400Validation() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void redirectIs302NoStoreAndIsCounted() throws Exception {
        String code = code(create("https://example.com/target?q=1", null));

        mvc.perform(get("/r/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target?q=1"))
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(get("/api/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.redirectCount").value(1))
                .andExpect(jsonPath("$.lastRedirectAt").exists());

        mvc.perform(get("/r/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/links/" + code)).andExpect(jsonPath("$.redirectCount").value(2));
    }

    @Test
    void unknownCodeIs404NotFoundForRedirectAndAnalytics() throws Exception {
        mvc.perform(get("/r/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/links/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void sameKeyAndSameBodyReplaysWith200() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String first = code(create("https://example.com/idem", key).andExpect(status().isCreated()));
        create("https://example.com/idem", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(first));
    }

    @Test
    void sameKeyWithDifferentBodyIs409Conflict() throws Exception {
        String key = "key-" + UUID.randomUUID();
        create("https://example.com/one", key).andExpect(status().isCreated());
        create("https://example.com/two", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void noKeyAlwaysCreatesANewLink() throws Exception {
        String a = code(create("https://example.com/same", null).andExpect(status().isCreated()));
        String b = code(create("https://example.com/same", null).andExpect(status().isCreated()));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void overlongOrBlankIdempotencyKeyIs400() throws Exception {
        create("https://example.com/k", "k".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        create("https://example.com/k", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    private ResultActions create(String url, String idempotencyKey) throws Exception {
        var request = post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":" + quote(url) + "}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private static String code(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.code");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
