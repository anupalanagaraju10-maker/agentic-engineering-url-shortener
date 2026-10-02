package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.rules.BehaviorStatement;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * DOCS: API and behavior documentation of the run's change, generated from the approved design, the
 * registry's recorded behavior statements and the implementation evidence (plan node contract). Exit
 * condition: every required section is present and non-empty. The FALLBACK template is added in Phase 6.
 */
@Component
public class DocsExecutor implements StageExecutor {

    public static final List<String> REQUIRED_SECTIONS = List.of("Overview", "Behavior", "API", "Data",
            "Implementation", "Validation", "Traceability", "Limitations");

    private final CapabilityRegistry registry;

    public DocsExecutor(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Node node() {
        return Node.DOCS;
    }

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> understand = Outputs.of(context, Node.UNDERSTAND);
        Map<String, Object> design = Outputs.of(context, Node.DESIGN);
        Map<String, Object> evidence = Outputs.of(context, Node.IMPLEMENT);
        List<Capability> capabilities = Outputs.capabilities(context);

        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Overview", "Requirement: " + understand.getOrDefault("requirement", context.requirement())
                + "\n\nNormalized: " + understand.getOrDefault("normalized", "") + "\n\nCapabilities: "
                + capabilities.stream().map(Enum::name).toList());
        StringBuilder behavior = new StringBuilder();
        for (Capability capability : capabilities) {
            for (BehaviorStatement s : registry.entry(capability).statements()) {
                behavior.append("- ").append(capability.name()).append(' ').append(s.code()).append(": ")
                        .append(s.text()).append('\n');
            }
        }
        sections.put("Behavior", behavior.toString().strip());
        sections.put("API", bullets(design.get("interfaceChanges")));
        sections.put("Data", bullets(design.get("dataChanges")));
        sections.put("Implementation", "Revision: " + evidence.getOrDefault("revision", "not recorded")
                + "\n\nSummary: " + evidence.getOrDefault("summary", "")
                + "\n\nChanged artifacts:\n" + bullets(evidence.get("changedArtifacts"))
                + "\n\nDesigned components cited by the evidence: " + Outputs.strings(evidence.get("coveredComponents"))
                + "\n\nDesigned components not cited: " + Outputs.strings(evidence.get("uncoveredComponents")));
        sections.put("Validation", "Planned tests and acceptance probes:\n" + bullets(design.get("testPlan"))
                + "\n\nSECURITY probes the validator with unsafe inputs; TEST runs the acceptance probes on the "
                + "running build.");
        StringBuilder trace = new StringBuilder("Requirement IDs: " + Outputs.strings(design.get("requirementIds")) + "\n");
        for (Object t : (List<?>) Outputs.of(context, Node.DECOMPOSE).getOrDefault("tasks", List.of())) {
            Map<?, ?> task = (Map<?, ?>) t;
            trace.append("- ").append(task.get("id")).append(" (").append(task.get("capability")).append("): ")
                    .append(Outputs.strings(task.get("requirementIds"))).append(" checked by ")
                    .append(Outputs.strings(task.get("acceptanceChecks"))).append('\n');
        }
        sections.put("Traceability", trace.toString().strip());
        sections.put("Limitations", "- No authentication; actor types are self-declared (EXC-003).\n"
                + "- Host names that resolve to private addresses are not blocked; only literals are checked (EXC-009).\n"
                + "- Implementation evidence is self-reported; TEST and SECURITY verify behavior, not the revision.");

        StringBuilder markdown = new StringBuilder("# Change documentation\n");
        sections.forEach((title, body) -> markdown.append("\n## ").append(title).append("\n\n").append(body).append('\n'));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sections", sections);
        output.put("markdown", markdown.toString());
        return StageResult.success(output, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        if (!(output.get("sections") instanceof Map<?, ?> sections)) {
            return Optional.of("documentation has no sections");
        }
        List<String> missing = REQUIRED_SECTIONS.stream()
                .filter(s -> sections.get(s) == null || sections.get(s).toString().isBlank()).toList();
        return missing.isEmpty() ? Optional.empty() : Optional.of("required documentation sections missing: " + missing);
    }

    @Override
    public boolean supportsFallback() {
        return true;
    }

    /**
     * FALLBACK template (FR-REL-005, ADR-0005 §4): every required section, filled only from the persisted
     * requirement, design and evidence identifiers, with no generated prose. Labelled FALLBACK.
     */
    @Override
    public StageResult fallback(StageContext context) {
        Map<String, Object> design = Outputs.of(context, Node.DESIGN);
        Map<String, Object> evidence = Outputs.of(context, Node.IMPLEMENT);
        String marker = "(fallback documentation: the detailed generator was unavailable)";
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Overview", "Requirement: " + context.requirement() + "\n" + marker);
        sections.put("Behavior", "Capabilities: " + Outputs.capabilities(context).stream().map(Enum::name).toList());
        sections.put("API", bullets(design.get("interfaceChanges")));
        sections.put("Data", bullets(design.get("dataChanges")));
        sections.put("Implementation", "Revision: " + evidence.getOrDefault("revision", "not recorded"));
        sections.put("Validation", bullets(design.get("testPlan")));
        sections.put("Traceability", "Requirement IDs: " + Outputs.strings(design.get("requirementIds")));
        sections.put("Limitations", "Detailed documentation was not generated; regenerate DOCS through rework.");
        StringBuilder markdown = new StringBuilder("# Change documentation (fallback)\n");
        sections.forEach((title, body) -> markdown.append("\n## ").append(title).append("\n\n").append(body).append('\n'));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sections", sections);
        output.put("markdown", markdown.toString());
        return StageResult.success(output, Provenance.FALLBACK);
    }

    private static String bullets(Object values) {
        List<String> items = Outputs.strings(values);
        return items.isEmpty() ? "- none" : "- " + String.join("\n- ", items);
    }
}
