package com.agentic.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T012: the application plane ({@code link}) never depends on the control plane ({@code workflow})
 * (NFR-003, ADR-0001). The dependency may only point from workflow to link.
 */
class PlaneBoundaryTest {

    private static final Path LINK_SOURCES = Path.of("src/main/java/com/agentic/shortener/link");
    private static final String FORBIDDEN = "com.agentic.shortener.workflow";

    @Test
    void linkPackageDoesNotReferenceWorkflowPackage() throws IOException {
        List<Path> offenders;
        if (!Files.isDirectory(LINK_SOURCES)) {
            offenders = List.of(); // no application-plane sources yet (they arrive in Phase 4)
        } else {
            try (Stream<Path> files = Files.walk(LINK_SOURCES)) {
                offenders = files.filter(p -> p.toString().endsWith(".java"))
                        .filter(PlaneBoundaryTest::referencesWorkflow)
                        .toList();
            }
        }
        assertThat(offenders).as("link sources referencing %s", FORBIDDEN).isEmpty();
    }

    @Test
    void detectorRecognisesAViolation() {
        assertThat(violates("import com.agentic.shortener.workflow.engine.Node;")).isTrue();
        assertThat(violates("import com.agentic.shortener.common.ApiException;")).isFalse();
    }

    private static boolean referencesWorkflow(Path file) {
        try {
            return violates(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean violates(String source) {
        return source.contains(FORBIDDEN);
    }
}
