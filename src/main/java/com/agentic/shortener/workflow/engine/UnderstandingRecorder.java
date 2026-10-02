package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.workflow.persistence.WorkflowRun;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** After UNDERSTAND: stores the normalized requirement and change type on the run, and audits them. */
@Component
@Order(1)
public class UnderstandingRecorder implements PostStageHook {

    private final WorkflowStore store;

    public UnderstandingRecorder(WorkflowStore store) {
        this.store = store;
    }

    @Override
    public HookOutcome afterStage(WorkflowRun run, Node node, Map<Node, Map<String, Object>> outputs) {
        if (node == Node.UNDERSTAND) {
            store.recordUnderstanding(run.getId(), outputs.get(Node.UNDERSTAND));
        }
        return HookOutcome.proceed();
    }
}
