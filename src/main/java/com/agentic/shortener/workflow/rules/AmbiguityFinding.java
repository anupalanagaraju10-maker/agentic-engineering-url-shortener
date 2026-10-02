package com.agentic.shortener.workflow.rules;

import java.util.Map;

/** One triggered ambiguity rule: rule id, the matched (or missing) terms, and a fixed explanation. */
public record AmbiguityFinding(String ruleId, String matched, String explanation) {

    public Map<String, Object> toMap() {
        return Map.of("ruleId", ruleId, "matched", matched, "explanation", explanation);
    }
}
