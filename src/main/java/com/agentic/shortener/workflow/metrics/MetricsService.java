package com.agentic.shortener.workflow.metrics;

import com.agentic.shortener.workflow.metrics.MetricsCalculator.EventFact;
import com.agentic.shortener.workflow.metrics.MetricsCalculator.Filter;
import com.agentic.shortener.workflow.metrics.MetricsCalculator.RunFacts;
import com.agentic.shortener.workflow.persistence.AuditEventRepository;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.agentic.shortener.workflow.persistence.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Loads runs and their audit events (read-only) and derives the demonstration metrics from them. */
@Service
public class MetricsService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final WorkflowRunRepository runs;
    private final AuditEventRepository events;
    private final ObjectMapper json;

    public MetricsService(WorkflowRunRepository runs, AuditEventRepository events, ObjectMapper json) {
        this.runs = runs;
        this.events = events;
        this.json = json;
    }

    public Map<String, Object> metrics(Filter filter) {
        List<RunFacts> facts = new ArrayList<>();
        for (WorkflowRun run : runs.findAll()) {
            List<EventFact> trail = events.findByRunIdOrderBySeqAsc(run.getId()).stream()
                    .map(e -> new EventFact(e.getSeq(), e.getType(), e.getActorType(), e.getCreatedAt(),
                            payload(e.getPayloadJson())))
                    .toList();
            facts.add(new RunFacts(run.getId(), run.getStatus(), Boolean.TRUE.equals(run.getRecoverable()),
                    run.getFaultPlanJson() != null, run.getCreatedAt(), run.getEndedAt(), trail));
        }
        return MetricsCalculator.compute(facts, filter);
    }

    private Map<String, Object> payload(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
