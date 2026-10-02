package com.agentic.shortener.workflow.stages;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import com.agentic.shortener.link.UrlValidator;
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
import org.springframework.stereotype.Component;

/**
 * SECURITY: probes the real destination validator with unsafe inputs (FR-URL-003/016, NFR-001, research R15)
 * plus one safe control, so a validator that refuses everything also fails. Any accepted unsafe input is a
 * PERMANENT {@code IMPLEMENTATION_DEFECT}; SECURITY never falls back (plan reliability model).
 */
@Component
public class SecurityExecutor implements StageExecutor {

    /** Unsafe inputs, one per refused class of research R15 (schemes, localhost, private and mapped literals). */
    static final List<String> UNSAFE = List.of(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,hello",
            "ftp://example.com/file",
            "http://localhost/",
            "http://api.localhost/",
            "http://0.0.0.0/",
            "http://127.0.0.1/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/",
            "http://[::]/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[fe80::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://2130706433/",
            "http://0x7f000001/",
            "https://" + "a".repeat(2050) + ".example.com/");
    static final String SAFE_CONTROL = "https://example.com/";

    private final UrlValidator validator;

    public SecurityExecutor(UrlValidator validator) {
        this.validator = validator;
    }

    @Override
    public Node node() {
        return Node.SECURITY;
    }

    @Override
    public StageResult execute(StageContext context) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        int rejected = 0;
        for (String input : UNSAFE) {
            String outcome = outcome(input);
            boolean passed = outcome.equals("REJECTED");
            rejected += passed ? 1 : 0;
            if (!passed) {
                accepted.add(input);
            }
            checks.add(check(input, "REJECTED", outcome));
        }
        String control = outcome(SAFE_CONTROL);
        checks.add(check(SAFE_CONTROL, "ACCEPTED", control));

        if (!accepted.isEmpty()) {
            return StageResult.failure(FailureClass.PERMANENT, "IMPLEMENTATION_DEFECT",
                    "unsafe destination addresses were accepted: " + accepted);
        }
        if (!control.equals("ACCEPTED")) {
            return StageResult.failure(FailureClass.PERMANENT, "IMPLEMENTATION_DEFECT",
                    "the safe control address was refused: " + SAFE_CONTROL);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("checks", checks);
        output.put("unsafeRejected", rejected);
        output.put("unsafeTotal", UNSAFE.size());
        output.put("limitation", "host names are never resolved, so a name that resolves to a private address "
                + "is not blocked (EXC-009)");
        return StageResult.success(output, Provenance.ACTUAL);
    }

    @Override
    public Optional<String> checkExit(Map<String, Object> output) {
        Object rejected = output.get("unsafeRejected");
        Object total = output.get("unsafeTotal");
        return rejected != null && rejected.equals(total) && !Outputs.strings(output.get("checks")).isEmpty()
                ? Optional.empty()
                : Optional.of("not every unsafe input was rejected");
    }

    private String outcome(String input) {
        try {
            validator.validate(input);
            return "ACCEPTED";
        } catch (ApiException e) {
            return e.getCategory() == ErrorCategory.VALIDATION ? "REJECTED" : "ERROR:" + e.getCategory();
        }
    }

    private static Map<String, Object> check(String input, String expected, String actual) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("input", input.length() > 80 ? input.substring(0, 40) + "…(" + input.length() + " chars)" : input);
        check.put("expected", expected);
        check.put("actual", actual);
        check.put("passed", expected.equals(actual));
        return check;
    }
}
