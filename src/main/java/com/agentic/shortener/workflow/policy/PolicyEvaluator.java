package com.agentic.shortener.workflow.policy;

import com.agentic.shortener.workflow.engine.HookOutcome;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.PostStageHook;
import com.agentic.shortener.workflow.engine.WorkflowStore;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Evaluates policy v1 checks after their bound node and persists every result (FR-POL-001..004).
 * Mandatory FAIL ⇒ non-recoverable safe-stop. EXCEPTION_REQUESTED ⇒ the run waits for a HUMAN exception
 * decision (run status AWAITING_APPROVAL, pendingAction EXCEPTION:&lt;checkId&gt;; the evaluated node has already
 * succeeded, so the run, not the node, waits). AUD-01 is computed by RELEASE_READINESS and recorded here.
 */
@Component
@Order(2)
public class PolicyEvaluator implements PostStageHook {

    private final WorkflowStore store;

    public PolicyEvaluator(WorkflowStore store) {
        this.store = store;
    }

    @Override
    public HookOutcome afterStage(WorkflowRun run, Node node, Map<Node, Map<String, Object>> outputs) {
        Map<String, PolicyOutcome> results = new LinkedHashMap<>();
        Map<String, Object> understand = outputs.getOrDefault(Node.UNDERSTAND, Map.of());
        String normalized = String.valueOf(understand.getOrDefault("normalized", ""));
        if (node == Node.UNDERSTAND) {
            results.put("PRIV-01", PolicyCatalog.priv01(normalized));
        } else if (node == Node.DESIGN) {
            Map<String, Object> design = outputs.getOrDefault(Node.DESIGN, Map.of());
            results.put("SEC-01", PolicyCatalog.sec01(normalized));
            results.put("CHG-01", PolicyCatalog.chg01(String.valueOf(understand.get("changeType")),
                    outputs.get(Node.IMPACT_ANALYSIS)));
            results.put("DEP-01", PolicyCatalog.dep01(strings(design.get("dependencies"))));
        } else if (node == Node.RELEASE_READINESS) {
            // AUD-01 is computed by the readiness stage from the persisted trail; recorded here (H1).
            Object aud01 = outputs.getOrDefault(Node.RELEASE_READINESS, Map.of()).get("aud01");
            Map<?, ?> audit = aud01 instanceof Map<?, ?> m ? m : Map.of();
            PolicyResult result = "PASS".equals(audit.get("result")) ? PolicyResult.PASS : PolicyResult.FAIL;
            results.put("AUD-01", new PolicyOutcome(result, String.valueOf(audit.get("reason"))));
        } else {
            return HookOutcome.proceed();
        }

        HookOutcome outcome = HookOutcome.proceed();
        for (Map.Entry<String, PolicyOutcome> e : results.entrySet()) {
            PolicyCatalog.CheckDefinition check = PolicyCatalog.check(e.getKey());
            PolicyOutcome result = e.getValue();
            store.recordPolicyEvaluation(run.getId(), PolicyCatalog.VERSION, check.id(), check.domain(), node,
                    check.mandatory(), result.result().name(), result.reason(), run.getPlanVersion());
            if (check.mandatory() && result.result() == PolicyResult.FAIL && outcome.kind() != HookOutcome.Kind.SAFE_STOP) {
                outcome = HookOutcome.safeStop("Mandatory policy " + check.id() + " FAIL: " + result.reason(), false);
            } else if (result.result() == PolicyResult.EXCEPTION_REQUESTED && outcome.kind() == HookOutcome.Kind.CONTINUE) {
                outcome = HookOutcome.waitForException(check.id(), result.reason());
            }
        }
        return outcome;
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> l) {
            l.forEach(v -> list.add(String.valueOf(v)));
        }
        return list;
    }
}
