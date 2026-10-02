package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.workflow.persistence.WorkflowRun;
import java.util.Map;

/**
 * Runs on the coordinating thread after a stage SUCCEEDED (H1): records derived state (e.g. the normalized
 * requirement) and evaluates policy. Hooks may write through {@link WorkflowStore}; executors may not.
 */
public interface PostStageHook {

    HookOutcome afterStage(WorkflowRun run, Node node, Map<Node, Map<String, Object>> outputs);
}
