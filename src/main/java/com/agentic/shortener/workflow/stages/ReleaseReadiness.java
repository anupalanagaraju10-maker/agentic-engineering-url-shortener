package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.workflow.engine.AuditEventType;
import com.agentic.shortener.workflow.engine.DecisionType;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.StageStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The RELEASE_READINESS rules as a pure function of the persisted run state (plan node contract, FR-POL-006,
 * AUD-01, H3). A run is ready only if no mandatory policy failure is unresolved, every exception is approved,
 * every task has a passing acceptance check, AUD-01 passes and no probe link of the run remains.
 */
public final class ReleaseReadiness {

    private ReleaseReadiness() {
    }

    public record PolicyRow(String checkId, boolean mandatory, String result, Long resolutionDecisionId,
            int planVersion) {
    }

    public record DecisionRow(long id, DecisionType type, String gate, int planVersion) {
    }

    public record EventRow(int seq, AuditEventType type, Node node, Long decisionId) {
    }

    public record Input(int planVersion, Map<Node, StageStatus> stages, Map<Node, Map<String, Object>> outputs,
            List<PolicyRow> policies, List<DecisionRow> decisions, List<EventRow> events, long probeLinksRemaining) {
    }

    public static Map<String, Object> evaluate(Input in) {
        List<String> blockers = new ArrayList<>();

        List<Map<String, Object>> policies = policies(in, blockers);
        List<Map<String, Object>> taskChecks = taskChecks(in, blockers);
        Map<String, Object> aud01 = aud01(in);
        if ("FAIL".equals(aud01.get("result"))) {
            blockers.add("AUD-01 FAIL: " + aud01.get("reason"));
        }
        if (in.probeLinksRemaining() > 0) {
            blockers.add(in.probeLinksRemaining() + " probe link(s) of this run still exist (compensation required)");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", blockers.isEmpty());
        result.put("blockers", blockers);
        result.put("taskChecks", taskChecks);
        result.put("policies", policies);
        result.put("aud01", aud01);
        result.put("probeLinksRemaining", in.probeLinksRemaining());
        result.put("residualRisks", residualRisks(in));
        return result;
    }

    /** Latest result per check at the current plan version; FAIL and unapproved exceptions block release. */
    private static List<Map<String, Object>> policies(Input in, List<String> blockers) {
        Map<String, PolicyRow> latest = new LinkedHashMap<>();
        in.policies().stream().filter(p -> p.planVersion() == in.planVersion()).forEach(p -> latest.put(p.checkId(), p));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PolicyRow p : latest.values()) {
            boolean resolved = true;
            if ("FAIL".equals(p.result()) && p.mandatory()) {
                resolved = false;
                blockers.add("unresolved mandatory policy failure " + p.checkId());
            } else if ("EXCEPTION_REQUESTED".equals(p.result())) {
                resolved = p.resolutionDecisionId() != null && in.decisions().stream().anyMatch(
                        d -> d.id() == p.resolutionDecisionId() && d.type() == DecisionType.EXCEPTION_APPROVED);
                if (!resolved) {
                    blockers.add("policy exception " + p.checkId() + " is not approved by a HUMAN decision");
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkId", p.checkId());
            row.put("result", p.result());
            row.put("resolved", resolved);
            rows.add(row);
        }
        return rows;
    }

    /** Every task needs all of its acceptance checks to have passed in TEST. */
    private static List<Map<String, Object>> taskChecks(Input in, List<String> blockers) {
        Map<String, Boolean> probes = new LinkedHashMap<>();
        for (Object p : (List<?>) in.outputs().getOrDefault(Node.TEST, Map.of()).getOrDefault("probes", List.of())) {
            Map<?, ?> probe = (Map<?, ?>) p;
            probes.put(String.valueOf(probe.get("id")), Boolean.TRUE.equals(probe.get("passed")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object t : (List<?>) in.outputs().getOrDefault(Node.DECOMPOSE, Map.of()).getOrDefault("tasks", List.of())) {
            Map<?, ?> task = (Map<?, ?>) t;
            List<String> checks = Outputs.strings(task.get("acceptanceChecks"));
            boolean passed = !checks.isEmpty() && checks.stream().allMatch(c -> probes.getOrDefault(c, false));
            if (!passed) {
                blockers.add("task " + task.get("id") + " has no passing acceptance check for " + checks);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", String.valueOf(task.get("id")));
            row.put("capability", String.valueOf(task.get("capability")));
            row.put("checks", checks);
            row.put("passed", passed);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            blockers.add("no decomposed task to verify");
        }
        return rows;
    }

    /**
     * AUD-01 (research R11): every executed or skipped node, every branch, gate decision and implementation
     * record has its audit event, and the run's sequence is gap-free from 1.
     */
    static Map<String, Object> aud01(Input in) {
        List<String> missing = new ArrayList<>();
        in.stages().forEach((node, status) -> {
            if (status == StageStatus.SUCCEEDED && !has(in, AuditEventType.STAGE_SUCCEEDED, node)) {
                missing.add("STAGE_SUCCEEDED for " + node);
            }
            if (status == StageStatus.SKIPPED && !has(in, AuditEventType.STAGE_SKIPPED, node)) {
                missing.add("STAGE_SKIPPED for " + node);
            }
        });
        for (DecisionRow d : in.decisions()) {
            AuditEventType expected = switch (d.type()) {
                case APPROVAL -> AuditEventType.APPROVAL_GRANTED;
                case REJECTION -> AuditEventType.APPROVAL_REJECTED;
                case IMPLEMENTATION_EVIDENCE -> AuditEventType.IMPLEMENTATION_RECORDED;
                case EXCEPTION_APPROVED -> AuditEventType.EXCEPTION_APPROVED;
                case EXCEPTION_REJECTED -> AuditEventType.EXCEPTION_REJECTED;
                default -> null;
            };
            if (d.type() == DecisionType.BRANCH) {
                Node node = Node.valueOf(d.gate());
                if (!has(in, AuditEventType.BRANCH_TAKEN, node)) {
                    missing.add("BRANCH_TAKEN for decision " + d.id() + " (" + node + ")");
                }
            } else if (expected != null && in.events().stream()
                    .noneMatch(e -> e.type() == expected && Objects.equals(e.decisionId(), d.id()))) {
                missing.add(expected + " for decision " + d.id() + " (" + d.type() + ")");
            }
        }
        long refusals = in.decisions().stream().filter(d -> d.type() == DecisionType.DECISION_REFUSED).count();
        long refusalEvents = in.events().stream().filter(e -> e.type() == AuditEventType.DECISION_REFUSED).count();
        if (refusalEvents < refusals) {
            missing.add("DECISION_REFUSED events (" + refusalEvents + " for " + refusals + " refusals)");
        }
        Set<Integer> seqs = in.events().stream().map(EventRow::seq).collect(Collectors.toSet());
        boolean contiguous = !seqs.isEmpty() && seqs.size() == in.events().size()
                && seqs.stream().mapToInt(Integer::intValue).max().getAsInt() == seqs.size()
                && seqs.contains(1);

        Map<String, Object> aud01 = new LinkedHashMap<>();
        if (!contiguous) {
            aud01.put("result", "FAIL");
            aud01.put("reason", "the audit sequence is not gap-free from 1 (" + seqs.size() + " events)");
        } else if (!missing.isEmpty()) {
            aud01.put("result", "FAIL");
            aud01.put("reason", missing.size() + " audit event(s) missing");
        } else {
            aud01.put("result", "PASS");
            aud01.put("reason", "every executed node, branch, gate decision and implementation record has its "
                    + "audit event; " + seqs.size() + " events, gap-free; retention per ASM-009");
        }
        aud01.put("missing", missing);
        return aud01;
    }

    private static List<String> residualRisks(Input in) {
        List<String> risks = new ArrayList<>();
        for (String component : Outputs.strings(in.outputs().getOrDefault(Node.IMPLEMENT, Map.of())
                .get("uncoveredComponents"))) {
            risks.add("Designed component " + component + " is not cited in the implementation evidence");
        }
        risks.add("Implementation evidence is self-reported; the revision is not verified against Git "
                + "(behavior is verified by TEST and SECURITY probes)");
        List<String> capabilities = Outputs.strings(in.outputs().getOrDefault(Node.UNDERSTAND, Map.of())
                .get("capabilities"));
        if (capabilities.contains("CREATE_LINK")) {
            risks.add("Host names that resolve to private addresses are not blocked; only IP literals are checked "
                    + "(EXC-009)");
        }
        risks.add("No authentication; actor types are self-declared (EXC-003)");
        return risks;
    }

    private static boolean has(Input in, AuditEventType type, Node node) {
        return in.events().stream().anyMatch(e -> e.type() == type && e.node() == node);
    }
}
