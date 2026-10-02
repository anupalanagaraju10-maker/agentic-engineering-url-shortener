package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Demonstration fault injection (FR-REL-011, ADR-0005 §10, CHK038). Validates a fault plan, which is stored
 * with the run and marks it as an injected run, and hands each attempt its {@link FaultPoint}. Faults fire
 * after the executor's work and before the completion commit.
 */
@Component
public class FaultInjector {

    private static final TypeReference<List<Fault>> FAULTS = new TypeReference<>() {
    };
    private static final WorkflowGraph GRAPH = WorkflowGraph.standard();

    private final ObjectMapper json;
    private final WorkflowProperties properties;

    public FaultInjector(ObjectMapper json, WorkflowProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.faultInjection().enabled();
    }

    /** Parses and validates the request's {@code faults}: automated nodes only, known types, times of at least 1. */
    public List<Fault> parse(List<Map<String, Object>> raw) {
        List<Fault> faults = new ArrayList<>();
        for (Map<String, Object> f : raw) {
            Node stage = enumValue(Node.class, f.get("stage"), "stage");
            if (GRAPH.definition(stage).kind() != NodeKind.AUTOMATED) {
                throw invalid("faults are allowed on automated nodes only, not " + stage);
            }
            FaultType type = enumValue(FaultType.class, f.get("type"), "type");
            int times = (int) number(f.getOrDefault("times", 1), "times");
            if (times < 1) {
                throw invalid("times must be at least 1");
            }
            long delayMs = number(f.getOrDefault("delayMs", 300), "delayMs");
            if (delayMs < 0) {
                throw invalid("delayMs must not be negative");
            }
            faults.add(new Fault(stage, type, times, delayMs));
        }
        return faults;
    }

    public String toJson(List<Fault> faults) {
        try {
            return faults == null || faults.isEmpty() ? null : json.writeValueAsString(faults);
        } catch (Exception e) {
            throw new IllegalArgumentException("fault plan is not serializable", e);
        }
    }

    /** The fault point of one attempt (attempt numbers start at 1); {@link FaultPoint#NONE} if none applies. */
    public FaultPoint pointFor(WorkflowRun run, Node node, int attempt) {
        if (run.getFaultPlanJson() == null) {
            return FaultPoint.NONE;
        }
        for (Fault fault : plan(run)) {
            if (fault.stage() == node && attempt <= fault.times()) {
                return new FaultPoint(fault, properties.stageTimeout().toMillis() + 2_000);
            }
        }
        return FaultPoint.NONE;
    }

    private List<Fault> plan(WorkflowRun run) {
        try {
            return json.readValue(run.getFaultPlanJson(), FAULTS);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt fault plan for run " + run.getId(), e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String field) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw invalid("unknown fault " + field + ": " + value);
        }
    }

    private static long number(Object value, String field) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw invalid(field + " must be a number");
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST, reason);
    }
}
