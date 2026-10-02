package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

/** T053: storage unavailable (FR-URL-013, 014, 017): 503 never 404/410; analytics failure still redirects. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LinkStorageFailureTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private HealthContributorRegistry health;

    @MockitoBean
    private LinkRepository links;

    @Test
    void createIs503StorageUnavailableWithNoCode() throws Exception {
        when(links.existsByCode(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        when(links.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.category").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.detail").value("Storage is unavailable"));
    }

    @Test
    void redirectIs503WhenTheLookupFailsNeverNotFound() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        mvc.perform(get("/r/abcdefg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.category").value("STORAGE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/links/abcdefg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.category").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void redirectIs503WhenNoTransactionCanBeOpened() throws Exception {
        when(links.findByCode(anyString())).thenThrow(new CannotCreateTransactionException("no connection"));
        mvc.perform(get("/r/abcdefg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.category").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void analyticsFailureStillRedirectsAndIsLogged(CapturedOutput output) throws Exception {
        Link link = new Link("abcdefg", "https://example.com/still", Instant.now(), null);
        when(links.findByCode("abcdefg")).thenReturn(Optional.of(link));
        when(links.recordRedirect(any(), any())).thenThrow(new DataAccessResourceFailureException("write failed"));

        mvc.perform(get("/r/abcdefg"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/still"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(output.getOut()).contains("Analytics recording failed for code abcdefg");
    }

    @Test
    void healthIncludesStorageAndIsDownWhenTheDatasourceFails() throws Exception {
        assertThat(health.getContributor("db")).as("storage is part of the health indication").isNotNull();

        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("db down"));
        assertThat(new DataSourceHealthIndicator(broken).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
