package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** INTAKE: records the requirement (entry: non-blank, at most 4000 characters). */
@Component
public class IntakeExecutor implements StageExecutor {

    static final int MAX_LENGTH = 4000;

    @Override
    public Node node() {
        return Node.INTAKE;
    }

    @Override
    public Optional<String> checkEntry(StageContext context) {
        String requirement = context.requirement();
        if (requirement == null || requirement.isBlank()) {
            return Optional.of("requirement is blank");
        }
        if (requirement.length() > MAX_LENGTH) {
            return Optional.of("requirement exceeds " + MAX_LENGTH + " characters");
        }
        return Optional.empty();
    }

    @Override
    public StageResult execute(StageContext context) {
        return StageResult.success(Map.of("requirement", context.requirement(), "length", context.requirement().length()),
                Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        return output.get("requirement") == null ? Optional.of("requirement not stored") : Optional.empty();
    }
}
