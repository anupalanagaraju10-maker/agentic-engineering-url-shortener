package com.agentic.shortener.workflow.policy;

/** Result of one policy check with its reason. */
public record PolicyOutcome(PolicyResult result, String reason) {
}
