package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import com.agentic.shortener.workflow.policy.PolicyCatalog;
import com.agentic.shortener.workflow.rules.BehaviorStatement;
import com.agentic.shortener.workflow.rules.Capability;
import com.agentic.shortener.workflow.rules.CapabilityEntry;
import com.agentic.shortener.workflow.rules.CapabilityRegistry;
import com.agentic.shortener.workflow.rules.CapabilityStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * IMPACT_ANALYSIS (brownfield only): a ten-area report built deterministically from the capability
 * registry (FR-SCN-002, research R6). It is accurate but template-based, not code analysis; its
 * credibility comes from HUMAN review at DESIGN_APPROVAL.
 */
@Component
public class ImpactAnalysisExecutor implements StageExecutor {

    static final List<String> DOCUMENTATION = List.of("specs/001-agentic-sdlc-url-shortener/contracts/openapi.yaml",
            "specs/001-agentic-sdlc-url-shortener/quickstart.md", "README.md");

    private final CapabilityRegistry registry;

    public ImpactAnalysisExecutor(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Node node() {
        return Node.IMPACT_ANALYSIS;
    }

    @Override
    public StageResult execute(StageContext context) {
        List<Capability> requested = Outputs.capabilities(context);
        Set<String> affected = new LinkedHashSet<>();
        Set<String> interfaces = new LinkedHashSet<>();
        Set<String> data = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        for (Capability capability : requested) {
            CapabilityEntry entry = registry.entry(capability);
            affected.addAll(entry.designComponents());
            interfaces.addAll(entry.interfaces());
            data.addAll(entry.dataChanges());
            tests.addAll(entry.plannedTests());
        }

        List<String> currentBehavior = new ArrayList<>();
        List<String> regressionRisks = new ArrayList<>();
        Set<Capability> touchedImplemented = new LinkedHashSet<>();
        for (CapabilityEntry entry : registry.entries()) {
            boolean overlaps = entry.designComponents().stream().anyMatch(affected::contains);
            if (entry.status() == CapabilityStatus.IMPLEMENTED && overlaps) {
                touchedImplemented.add(entry.capability());
                for (BehaviorStatement s : entry.statements()) {
                    currentBehavior.add(entry.capability() + " " + s.code() + ": " + s.text());
                }
                regressionRisks.add("Recorded behavior of " + entry.capability() + " must still hold ("
                        + entry.statements().get(0).code() + ".." + entry.statements().get(entry.statements().size() - 1)
                        .code() + ")");
                tests.addAll(entry.plannedTests());
            }
        }
        if (currentBehavior.isEmpty()) {
            currentBehavior.add("No implemented capability is affected (behavior is new)");
            regressionRisks.add("No existing behavior is affected");
        }

        boolean securityRelevant = touchedImplemented.contains(Capability.CREATE_LINK)
                || touchedImplemented.contains(Capability.REDIRECT) || requested.contains(Capability.CREATE_LINK)
                || requested.contains(Capability.REDIRECT);
        String securityReliability = securityRelevant
                ? "URL validation and/or redirect resolution are touched: re-run the SECURITY probes; storage-failure "
                        + "behavior (FR-URL-013) and the analytics-failure rule (FR-URL-017) must be preserved"
                : "No security-sensitive component is affected; reliability behavior is unchanged";
        String rollback = data.isEmpty()
                ? "Code-only change: roll back by reverting the commit"
                : "Schema changes are additive Flyway migrations: roll back by reverting the code and adding a "
                        + "corrective migration; applied migrations are never edited";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("currentBehavior", currentBehavior);
        report.put("requestedBehavior", context.requirement());
        report.put("affectedComponents", List.copyOf(affected));
        report.put("interfaces", List.copyOf(interfaces));
        report.put("data", data.isEmpty() ? List.of("no data change") : List.copyOf(data));
        report.put("tests", List.copyOf(tests));
        report.put("documentation", DOCUMENTATION);
        report.put("regressionRisks", regressionRisks);
        report.put("securityReliabilityImpact", securityReliability);
        report.put("rollbackCompensation", rollback);
        report.put("dataFlows", dataFlows(requested)); // additive (T133); not one of CHG-01's ten areas
        return StageResult.success(report, Provenance.ACTUAL);
    }

    /**
     * Request → controller → service → persistence → data paths of each requested capability (assignment §4.3
     * "data flows"), built from the registry's interfaces, design components and data changes. Deterministic.
     */
    private List<String> dataFlows(List<Capability> requested) {
        List<String> flows = new ArrayList<>();
        for (Capability capability : requested) {
            CapabilityEntry entry = registry.entry(capability);
            String controllers = layer(entry.designComponents(), c -> c.endsWith("Controller"));
            String services = layer(entry.designComponents(), c -> !c.endsWith("Controller") && !isPersistence(c));
            String persistence = layer(entry.designComponents(), ImpactAnalysisExecutor::isPersistence);
            String data = entry.dataChanges().isEmpty() ? "no data change" : String.join(", ", entry.dataChanges());
            for (String endpoint : entry.interfaces()) {
                flows.add(capability + ": " + endpoint + " → " + controllers + " → " + services + " → " + persistence
                        + " → " + data);
            }
        }
        return flows;
    }

    private static boolean isPersistence(String component) {
        return component.endsWith("Repository") || component.equals("Link") || component.endsWith("Record");
    }

    private static String layer(List<String> components, java.util.function.Predicate<String> member) {
        List<String> selected = components.stream().filter(member).toList();
        return selected.isEmpty() ? "(none)" : String.join(", ", selected);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        List<String> missing = PolicyCatalog.IMPACT_AREAS.stream()
                .filter(a -> output.get(a) == null || output.get(a).toString().isBlank()
                        || "[]".equals(output.get(a).toString()))
                .toList();
        return missing.isEmpty() ? Optional.empty() : Optional.of("impact areas missing: " + missing);
    }
}
