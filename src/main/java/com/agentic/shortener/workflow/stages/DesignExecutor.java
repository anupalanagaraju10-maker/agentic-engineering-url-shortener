package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.policy.PolicyCatalog;
import com.agentic.shortener.workflow.policy.PolicyResult;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityEntry;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import com.agentic.shortener.workflow.rules.RecordedBehaviorRules;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * DESIGN: components, interface/data changes, test plan, added dependencies, security sensitivity, the
 * run's requirement IDs, {@code implementationRequired} and {@code changesApprovedRequirements}
 * (research R6). Exit condition: every task is mapped to at least one component.
 */
@Component
public class DesignExecutor implements StageExecutor {

    private final CapabilityRegistry registry;

    public DesignExecutor(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Node node() {
        return Node.DESIGN;
    }

    @Override
    public StageResult execute(StageContext context) {
        String normalized = Outputs.normalized(context);
        List<Capability> capabilities = Outputs.capabilities(context);
        Set<String> components = new LinkedHashSet<>();
        Set<String> interfaces = new LinkedHashSet<>();
        Set<String> data = new LinkedHashSet<>();
        Set<String> testPlan = new LinkedHashSet<>();
        Map<String, Object> taskComponents = new LinkedHashMap<>();
        for (Object t : (List<?>) Outputs.of(context, Node.DECOMPOSE).getOrDefault("tasks", List.of())) {
            Map<?, ?> task = (Map<?, ?>) t;
            CapabilityEntry entry = registry.entry(Capability.valueOf(task.get("capability").toString()));
            taskComponents.put(task.get("id").toString(), entry.designComponents());
            components.addAll(entry.designComponents());
            interfaces.addAll(entry.interfaces());
            data.addAll(entry.dataChanges());
            testPlan.addAll(entry.plannedTests());
            testPlan.addAll(entry.probeIds());
        }

        boolean securitySensitive = capabilities.contains(Capability.CREATE_LINK)
                || capabilities.contains(Capability.REDIRECT)
                || PolicyCatalog.sec01(normalized).result() == PolicyResult.FAIL;

        Map<String, Object> design = new LinkedHashMap<>();
        design.put("components", List.copyOf(components));
        design.put("taskComponents", taskComponents);
        design.put("interfaceChanges", List.copyOf(interfaces));
        design.put("dataChanges", List.copyOf(data));
        design.put("testPlan", List.copyOf(testPlan));
        design.put("dependencies", PolicyCatalog.mentionedDependencies(normalized));
        design.put("securitySensitive", securitySensitive);
        design.put("requirementIds", Outputs.strings(Outputs.of(context, Node.DECOMPOSE).get("requirementIds")));
        design.put("implementationRequired",
                RecordedBehaviorRules.implementationRequired(normalized, capabilities, registry));
        design.put("changesApprovedRequirements", RecordedBehaviorRules.changesApprovedRequirements(normalized));
        design.put("changeType", Outputs.changeType(context));
        return StageResult.success(design, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        Object mapped = output.get("taskComponents");
        if (!(mapped instanceof Map<?, ?> map) || map.isEmpty()) {
            return Optional.of("no task is mapped to a component");
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (Outputs.strings(e.getValue()).isEmpty()) {
                return Optional.of("task " + e.getKey() + " is not mapped to any component");
            }
        }
        return Optional.empty();
    }
}
