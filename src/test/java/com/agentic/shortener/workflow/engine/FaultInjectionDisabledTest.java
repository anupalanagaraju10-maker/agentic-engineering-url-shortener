package com.agentic.shortener.workflow.engine;

import static com.agentic.shortener.workflow.api.WorkflowApiClient.SCN_A;
import static com.agentic.shortener.workflow.api.WorkflowApiClient.fault;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.workflow.api.WorkflowApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T079, default configuration (CHK038): fault injection is off, so any run with faults is refused. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaultInjectionDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aRunWithFaultsIsRefusedByDefault() throws Exception {
        new WorkflowApiClient(mvc).createWithFaults(SCN_A, List.of(fault("DOCS", "TRANSIENT", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("FAULT_INJECTION_DISABLED"));
    }
}
