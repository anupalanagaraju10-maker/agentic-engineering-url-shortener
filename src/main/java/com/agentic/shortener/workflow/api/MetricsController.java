package com.agentic.shortener.workflow.api;

import com.agentic.shortener.workflow.metrics.MetricsCalculator.Filter;
import com.agentic.shortener.workflow.metrics.MetricsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/metrics/workflows} (FR-OBS-004/005): demonstration metrics, optionally by fault injection. */
@RestController
public class MetricsController {

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/metrics/workflows")
    public Map<String, Object> workflows(@RequestParam(name = "faultInjected", required = false) Boolean faultInjected) {
        Filter filter = faultInjected == null ? Filter.ALL : faultInjected ? Filter.INJECTED_ONLY : Filter.NOT_INJECTED;
        return metrics.metrics(filter);
    }
}
