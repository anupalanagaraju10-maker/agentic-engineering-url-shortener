package com.agentic.shortener.workflow.engine;

import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.workflow.persistence.Decision;
import com.agentic.shortener.workflow.persistence.WorkflowRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Records evidence of the IMPLEMENT external action (ADR-0003 §8, ADR-0004 §3). The application never
 * writes code and never queries Git: it validates the structure, the scope (CHK004) and the ordering after
 * a valid design approval (CHK024). TEST/SECURITY verify behavior downstream.
 */
@Service
public class ImplementationEvidenceService {

    private static final Pattern REVISION = Pattern.compile("^[0-9a-f]{7,40}$");
    private static final Pattern REQUIREMENT_ID = Pattern.compile("^(FR|NFR)-[A-Z]*-?[0-9]{3}$");

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final DecisionService decisions;
    private final RunLocks locks;

    public ImplementationEvidenceService(WorkflowStore store, WorkflowEngine engine, DecisionService decisions,
            RunLocks locks) {
        this.store = store;
        this.engine = engine;
        this.decisions = decisions;
        this.locks = locks;
    }

    public record EvidenceCommand(Actor actor, Integer planVersion, String summary, List<String> changedArtifacts,
            String revision, String noChangeJustification, List<String> requirementIds) {
    }

    public WorkflowRun record(UUID runId, EvidenceCommand command) {
        return locks.withLock(runId, () -> {
            WorkflowRun run = store.loadRun(runId);
            decisions.validateActor(run, command.actor(), ActorAction.RECORD_IMPLEMENTATION, "implementation");
            if (run.getStatus() != RunStatus.AWAITING_IMPLEMENTATION
                    || !"RECORD_IMPLEMENTATION".equals(run.getPendingAction())) {
                throw refuse(run, command, ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                        "run is not waiting for implementation evidence (status " + run.getStatus() + ")");
            }
            if (command.planVersion() == null || !command.planVersion().equals(run.getPlanVersion())) {
                throw refuse(run, command, ErrorCategory.STALE_PLAN_VERSION, HttpStatus.CONFLICT,
                        "evidence is for plan version " + command.planVersion() + " but the run is at "
                                + run.getPlanVersion());
            }
            Map<String, Object> design = store.outputs(runId).getOrDefault(Node.DESIGN, Map.of());
            validateStructure(run, command, design);

            List<String> runScope = strings(design.get("requirementIds"));
            List<String> outOfScope = command.requirementIds().stream().filter(id -> !runScope.contains(id)).toList();
            if (!outOfScope.isEmpty()) {
                throw refuse(run, command, ErrorCategory.EVIDENCE_SCOPE_MISMATCH, HttpStatus.CONFLICT,
                        "requirement IDs outside this run: " + outOfScope + " (run scope " + runScope + ")");
            }
            Decision approval = validDesignApproval(run);
            if (approval == null || !Instant.now().isAfter(approval.getCreatedAt())) {
                throw refuse(run, command, ErrorCategory.INVALID_STATE, HttpStatus.CONFLICT,
                        "no valid DESIGN_APPROVAL precedes this evidence");
            }

            List<String> changed = command.changedArtifacts() == null ? List.of() : command.changedArtifacts();
            List<String> designed = strings(design.get("components"));
            List<String> covered = designed.stream().filter(c -> changed.stream().anyMatch(p -> mentions(p, c))).toList();
            List<String> uncovered = designed.stream().filter(c -> !covered.contains(c)).toList();

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("summary", command.summary());
            evidence.put("changedArtifacts", changed);
            evidence.put("revision", command.revision());
            evidence.put("noChangeJustification", command.noChangeJustification());
            evidence.put("requirementIds", command.requirementIds());
            evidence.put("designApprovalDecisionId", approval.getId());
            evidence.put("coveredComponents", covered);
            evidence.put("uncoveredComponents", uncovered);
            store.recordImplementation(runId, command.actor(), command.summary(), run.getPlanVersion(), evidence);
            engine.advance(runId);
            return store.loadRun(runId);
        });
    }

    private void validateStructure(WorkflowRun run, EvidenceCommand c, Map<String, Object> design) {
        if (c.summary() == null || c.summary().isBlank()) {
            throw invalid(run, c, "summary is required");
        }
        if (c.requirementIds() == null || c.requirementIds().isEmpty()
                || !c.requirementIds().stream().allMatch(id -> id != null && REQUIREMENT_ID.matcher(id).matches())) {
            throw invalid(run, c, "requirementIds must be a non-empty list of FR-/NFR- identifiers");
        }
        boolean changed = c.changedArtifacts() != null && !c.changedArtifacts().isEmpty();
        if (changed && (c.revision() == null || !REVISION.matcher(c.revision()).matches())) {
            throw invalid(run, c, "revision (7-40 lowercase hex characters) is required when artifacts changed");
        }
        if (!changed) {
            if (c.noChangeJustification() == null || c.noChangeJustification().isBlank()) {
                throw invalid(run, c, "noChangeJustification is required when no code/config/schema changed");
            }
            if (!Boolean.FALSE.equals(design.get("implementationRequired"))) {
                throw invalid(run, c, "the approved design requires implementation; a no-change record is refused");
            }
        }
    }

    /**
     * The latest APPROVAL at DESIGN_APPROVAL that nothing has superseded. A replan that affects the design
     * supersedes it (DECISION_INVALIDATED); a rework that starts below the gate preserves it (ADR-0004 §5/§6).
     */
    private Decision validDesignApproval(WorkflowRun run) {
        List<Decision> lineage = store.decisions(run.getId());
        Set<Long> superseded = lineage.stream().map(Decision::getSupersedesId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Decision latest = null;
        for (Decision d : lineage) {
            if (d.getType() == DecisionType.APPROVAL && Node.DESIGN_APPROVAL.name().equals(d.getGate())
                    && !superseded.contains(d.getId())) {
                latest = d;
            }
        }
        return latest;
    }

    private com.agentic.shortener.common.ApiException invalid(WorkflowRun run, EvidenceCommand c, String reason) {
        return refuse(run, c, ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST, reason);
    }

    private com.agentic.shortener.common.ApiException refuse(WorkflowRun run, EvidenceCommand c, ErrorCategory category,
            HttpStatus status, String reason) {
        return decisions.refuse(run, "implementation", c.actor(), category, status, reason);
    }

    private static boolean mentions(String path, String component) {
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file).equals(component);
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            c.forEach(v -> list.add(String.valueOf(v)));
        }
        return list;
    }
}
