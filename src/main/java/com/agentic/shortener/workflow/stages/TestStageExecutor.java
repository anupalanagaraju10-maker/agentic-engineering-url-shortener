package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.link.Link;
import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.workflow.engine.FailureClass;
import com.agentic.shortener.workflow.engine.Node;
import com.agentic.shortener.workflow.engine.Provenance;
import com.agentic.shortener.workflow.engine.StageContext;
import com.agentic.shortener.workflow.engine.StageExecutor;
import com.agentic.shortener.workflow.engine.StageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * TEST: runs every task's acceptance probes against the running build through {@link LinkService} (plan node
 * contract, FR-ORC-014). Probe links are real, tagged with the run id, and removed before the stage ends;
 * the evidence lives in the output and the audit trail. A failing probe after accepted evidence is a
 * PERMANENT {@code IMPLEMENTATION_DEFECT} (CHK036). The cancellation token is checked before every probe,
 * so a revoked attempt creates no further probe link (H3). Formal compensation events are added in Phase 6.
 */
@Component
public class TestStageExecutor implements StageExecutor {

    private final LinkService links;

    public TestStageExecutor(LinkService links) {
        this.links = links;
    }

    @Override
    public Node node() {
        return Node.TEST;
    }

    @Override
    public StageResult execute(StageContext context) {
        UUID runId = context.runId();
        List<Map<String, Object>> results = new ArrayList<>();
        int[] created = { 0 };
        try {
            for (Object t : (List<?>) Outputs.of(context, Node.DECOMPOSE).getOrDefault("tasks", List.of())) {
                Map<?, ?> task = (Map<?, ?>) t;
                for (String probeId : Outputs.strings(task.get("acceptanceChecks"))) {
                    if (context.cancellation().isRevoked()) {
                        return StageResult.failure(FailureClass.TRANSIENT, "CANCELLED",
                                "attempt revoked before " + probeId);
                    }
                    ProbeOutcome outcome = run(probeId, runId, created);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", probeId);
                    result.put("task", String.valueOf(task.get("id")));
                    result.put("capability", String.valueOf(task.get("capability")));
                    result.put("passed", outcome.passed());
                    result.put("detail", outcome.detail());
                    results.add(result);
                }
            }
        } catch (RuntimeException e) {
            return StageResult.failure(FailureClass.TRANSIENT, "PROBE_ERROR",
                    "probe could not run: " + e.getClass().getSimpleName());
        } finally {
            links.deleteProbeLinks(runId);
        }

        List<String> failed = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed")))
                .map(r -> r.get("id") + " (" + r.get("detail") + ")").toList();
        if (!failed.isEmpty()) {
            return StageResult.failure(FailureClass.PERMANENT, "IMPLEMENTATION_DEFECT",
                    "acceptance probes failed: " + String.join("; ", failed));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("probes", results);
        output.put("probeLinksCreated", created[0]);
        output.put("probeLinksRemaining", (int) links.countProbeLinks(runId));
        return StageResult.success(output, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        if (Outputs.strings(output.get("probes")).isEmpty()) {
            return Optional.of("no acceptance probe was executed");
        }
        Object remaining = output.get("probeLinksRemaining");
        if (!(remaining instanceof Number n) || n.intValue() != 0) {
            return Optional.of("probe links were not cleaned up: " + remaining);
        }
        return Optional.empty();
    }

    private record ProbeOutcome(boolean passed, String detail) {
    }

    private ProbeOutcome run(String probeId, UUID runId, int[] created) {
        String url = "https://example.com/probe/" + runId + "/" + probeId;
        Function<String, Link> create = target -> {
            created[0]++;
            return links.createProbeLink(target, runId, null).link();
        };
        switch (probeId) {
            case "probe.create-link" -> {
                Link link = create.apply(url);
                boolean ok = link.getCode().matches("^[0-9A-Za-z]{7}$")
                        && url.equals(links.get(link.getCode()).getOriginalUrl());
                return new ProbeOutcome(ok, "created code " + link.getCode() + " resolving to the original address");
            }
            case "probe.reject-unsafe-url" -> {
                return expectRefusal(() -> create.apply("javascript:alert(1)"), ErrorCategory.VALIDATION,
                        "javascript: address");
            }
            case "probe.redirect" -> {
                Link link = create.apply(url);
                String target = links.redirect(link.getCode());
                return new ProbeOutcome(url.equals(target), "redirect target " + (url.equals(target) ? "matches" : "was " + target));
            }
            case "probe.unknown-code" -> {
                return expectRefusal(() -> links.redirect("unknown-" + runId.toString().substring(0, 6)),
                        ErrorCategory.NOT_FOUND, "never-issued code");
            }
            case "probe.redirect-count" -> {
                Link link = create.apply(url);
                links.redirect(link.getCode());
                links.redirect(link.getCode());
                Link after = links.get(link.getCode());
                boolean ok = after.getRedirectCount() == 2 && after.getLastRedirectAt() != null;
                return new ProbeOutcome(ok, "2 redirects counted as " + after.getRedirectCount()
                        + ", lastRedirectAt " + (after.getLastRedirectAt() == null ? "missing" : "set"));
            }
            case "probe.idempotent-replay" -> {
                String key = "probe-" + runId + "-replay";
                created[0]++;
                LinkService.CreateResult first = links.createProbeLink(url, runId, key);
                LinkService.CreateResult second = links.createProbeLink(url, runId, key);
                boolean ok = second.replayed() && second.link().getCode().equals(first.link().getCode());
                return new ProbeOutcome(ok, "same key and request replayed the same code");
            }
            case "probe.idempotent-conflict" -> {
                String key = "probe-" + runId + "-conflict";
                created[0]++;
                links.createProbeLink(url, runId, key);
                return expectRefusal(() -> links.createProbeLink(url + "/other", runId, key), ErrorCategory.CONFLICT,
                        "same key with a different request");
            }
            default -> {
                return new ProbeOutcome(false, "no acceptance probe is implemented in this build");
            }
        }
    }

    private static ProbeOutcome expectRefusal(Runnable call, ErrorCategory expected, String what) {
        try {
            call.run();
            return new ProbeOutcome(false, what + " was accepted");
        } catch (ApiException e) {
            boolean ok = e.getCategory() == expected;
            return new ProbeOutcome(ok, what + " refused with " + e.getCategory());
        }
    }
}
