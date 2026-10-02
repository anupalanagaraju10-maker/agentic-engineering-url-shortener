package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.AmbiguityFinding;
import com.agentic.shortener.workflow.rules.AmbiguityRules;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import com.agentic.shortener.workflow.rules.RecordedBehaviorRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * UNDERSTAND: normalizes the requirement, maps capabilities (research R5), runs the ambiguity rules, and
 * classifies the change type (research R6). The engine records REQUIREMENT_NORMALIZED and
 * AMBIGUITY_DETECTED from this output (executors never write, H1).
 */
@Component
public class UnderstandExecutor implements StageExecutor {

    private final CapabilityRegistry registry;
    private final AmbiguityRules rules;

    public UnderstandExecutor(CapabilityRegistry registry, AmbiguityRules rules) {
        this.registry = registry;
        this.rules = rules;
    }

    @Override
    public Node node() {
        return Node.UNDERSTAND;
    }

    @Override
    public StageResult execute(StageContext context) {
        String normalized = AmbiguityRules.normalize(context.requirement());
        List<Capability> capabilities = AmbiguityRules.capabilities(normalized);
        List<AmbiguityFinding> findings = rules.evaluate(context.requirement());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("requirement", context.requirement());
        output.put("normalized", normalized);
        output.put("capabilities", capabilities.stream().map(Enum::name).toList());
        output.put("findings", findings.stream().map(AmbiguityFinding::toMap).toList());
        output.put("changeType", RecordedBehaviorRules.isBrownfield(normalized, capabilities, registry)
                ? "BROWNFIELD" : "GREENFIELD");
        return StageResult.success(output, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        for (String key : List.of("normalized", "capabilities", "findings", "changeType")) {
            if (!output.containsKey(key)) {
                return Optional.of("missing " + key);
            }
        }
        return Optional.empty();
    }
}
