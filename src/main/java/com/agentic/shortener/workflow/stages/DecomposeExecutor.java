package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityEntry;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * DECOMPOSE: one task per capability, each with acceptance checks and requirement IDs. The union of task
 * requirement IDs is the run's requirement-ID set (the scope for implementation evidence, CHK004). A
 * requirement with no known capability fails PERMANENT as out-of-vocabulary rather than being guessed.
 */
@Component
public class DecomposeExecutor implements StageExecutor {

    private final CapabilityRegistry registry;

    public DecomposeExecutor(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Node node() {
        return Node.DECOMPOSE;
    }

    @Override
    public Optional<String> checkEntry(StageContext context) {
        Object findings = Outputs.of(context, Node.UNDERSTAND).get("findings");
        boolean openAmbiguity = !Outputs.strings(findings).isEmpty();
        return openAmbiguity ? Optional.of("material ambiguity is still open") : Optional.empty();
    }

    @Override
    public StageResult execute(StageContext context) {
        List<Capability> capabilities = Outputs.capabilities(context);
        if (capabilities.isEmpty()) {
            return StageResult.failure(FailureClass.PERMANENT, "INVALID_INPUT",
                    "no known capability in the requirement (out of vocabulary); not guessed");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> requirementIds = new ArrayList<>();
        int n = 1;
        for (Capability capability : capabilities) {
            CapabilityEntry entry = registry.entry(capability);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", "TASK-" + n++);
            task.put("capability", capability.name());
            task.put("title", "Deliver " + capability.name() + " behavior");
            task.put("requirementIds", entry.requirementIds());
            task.put("acceptanceChecks", entry.probeIds());
            tasks.add(task);
            entry.requirementIds().stream().filter(id -> !requirementIds.contains(id)).forEach(requirementIds::add);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tasks", tasks);
        output.put("requirementIds", requirementIds);
        return StageResult.success(output, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        Object tasks = output.get("tasks");
        if (!(tasks instanceof List<?> list) || list.isEmpty()) {
            return Optional.of("no tasks produced");
        }
        for (Object t : list) {
            Map<?, ?> task = (Map<?, ?>) t;
            if (Outputs.strings(task.get("acceptanceChecks")).isEmpty()
                    || Outputs.strings(task.get("requirementIds")).isEmpty()) {
                return Optional.of("task " + task.get("id") + " lacks acceptance checks or requirement IDs");
            }
        }
        return Optional.empty();
    }
}
