package com.agentic.shortener.workflow.rules;

import java.util.List;

/**
 * Registry entry for one capability (research R6). Design data ({@code designComponents},
 * {@code interfaces}, {@code dataChanges}, {@code plannedTests}) drives DESIGN and IMPACT_ANALYSIS.
 * {@code componentClasses} and {@code testFiles} are filled when the capability becomes IMPLEMENTED;
 * {@code CapabilityRegistryTest} checks they exist.
 */
public record CapabilityEntry(Capability capability, CapabilityStatus status, List<String> vocabulary,
        List<String> requirementIds, List<BehaviorStatement> statements, List<String> probeIds,
        List<String> designComponents, List<String> interfaces, List<String> dataChanges, List<String> plannedTests,
        List<String> componentClasses, List<String> testFiles) {

    public CapabilityEntry withStatus(CapabilityStatus newStatus) {
        return new CapabilityEntry(capability, newStatus, vocabulary, requirementIds, statements, probeIds,
                designComponents, interfaces, dataChanges, plannedTests, componentClasses, testFiles);
    }
}
