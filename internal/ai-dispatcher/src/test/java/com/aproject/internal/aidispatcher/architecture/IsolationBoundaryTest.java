package com.aproject.internal.aidispatcher.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IsolationBoundaryTest {

    private static final String MAIN_APPLICATION_PACKAGE =
            "com.aproject.aidriven.mymobilesecretary";

    @Test
    void productionSourcesDoNotImportMainApplicationPackages() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("basedir"), "src", "main", "java");
        List<Path> violations;
        try (var paths = Files.walk(sourceRoot)) {
            violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::containsMainApplicationPackage)
                    .toList();
        }

        assertThat(violations)
                .as("AI Dispatcher production code must not import the main application")
                .isEmpty();
    }

    private boolean containsMainApplicationPackage(Path path) {
        try {
            return Files.readString(path).contains(MAIN_APPLICATION_PACKAGE);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not inspect " + path, failure);
        }
    }
}
