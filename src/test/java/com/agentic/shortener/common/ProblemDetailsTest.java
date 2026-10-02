package com.agentic.shortener.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** T011: structured Problem Details error format without stack traces (FR-URL-015, NFR-001). */
@WebMvcTest(controllers = ProblemDetailsTest.ProbeController.class)
@Import(ProblemDetailsTest.ProbeController.class) // nested test classes are excluded from scanning
class ProblemDetailsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void categoriesMatchTheOpenApiEnumExactly() {
        assertThat(Arrays.stream(ErrorCategory.values()).map(Enum::name))
                .containsExactly("VALIDATION", "NOT_FOUND", "EXPIRED", "CONFLICT", "INVALID_STATE",
                        "STALE_PLAN_VERSION", "EVIDENCE_SCOPE_MISMATCH", "CHANGE_CONTROL_REQUIRED",
                        "FAULT_INJECTION_DISABLED", "REPLAN_FAILED", "STORAGE_UNAVAILABLE",
                        "CODE_SPACE_EXHAUSTED", "INTERNAL");
    }

    @Test
    void applicationErrorBecomesProblemDetailWithCategory() throws Exception {
        mvc.perform(get("/test-probe/api-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("probe conflict"));
    }

    @Test
    void unexpectedErrorLeaksNoInternalDetails() throws Exception {
        String body = mvc.perform(get("/test-probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.category").value("INTERNAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret internal detail", "IllegalStateException", "at com.", "trace");
    }

    @Test
    void beanValidationFailureIsValidationCategory() throws Exception {
        mvc.perform(post("/test-probe/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void unknownRouteIsNotFoundCategory() throws Exception {
        mvc.perform(get("/test-probe/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    /** Test-only controller used to trigger each error path. */
    @RestController
    static class ProbeController {

        @GetMapping("/test-probe/api-error")
        void apiError() {
            throw new ApiException(ErrorCategory.CONFLICT, HttpStatus.CONFLICT, "probe conflict");
        }

        @GetMapping("/test-probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/test-probe/validated")
        void validated(@Valid @RequestBody Body body) {
        }

        record Body(@NotBlank String name) {
        }
    }
}
