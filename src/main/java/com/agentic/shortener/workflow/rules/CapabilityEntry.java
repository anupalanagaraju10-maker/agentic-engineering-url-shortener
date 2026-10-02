package com.agentic.shortener.workflow.rules;

import java.util.List;

/**
 * Registry entry for one capability (research R6). {@code componentClasses} and {@code testFiles} are
 * filled when the capability becomes IMPLEMENTED; {@code CapabilityRegistryTest} checks they exist.
 */
public record CapabilityEntry(Capability capability, CapabilityStatus status, List<String> vocabulary,
        List<String> requirementIds, List<BehaviorStatement> statements, List<String> probeIds,
        List<String> componentClasses, List<String> testFiles) {
}
