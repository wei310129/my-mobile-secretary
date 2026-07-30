package com.aproject.aidriven.mymobilesecretary.execution.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionCoreArchitectureTest {

    private static final Path EXECUTION_MAIN =
            Path.of(
                    "src/main/java/com/aproject/aidriven/mymobilesecretary/execution");

    @Test
    void coreDependsOnlyOnItsOwnedTypesAndJavaRuntime() throws IOException {
        Map<String, String> sources = productionSources();

        assertThat(sources).isNotEmpty();
        sources.forEach(
                (path, source) -> {
                    assertThat(source)
                            .as(path)
                            .doesNotContain(
                                    "mymobilesecretary.booking.",
                                    "mymobilesecretary.calendar.",
                                    "mymobilesecretary.intent.",
                                    "mymobilesecretary.reminder.",
                                    "mymobilesecretary.travel.",
                                    "org.springframework.",
                                    "jakarta.persistence.");
                });
    }

    @Test
    void coreUsesInjectedClockAndHasNoPersistenceOrMutationEscapeHatch()
            throws IOException {
        productionSources()
                .forEach(
                        (path, source) -> {
                            assertThat(source)
                                    .as(path)
                                    .doesNotContain(
                                            "Instant.now(",
                                            "System.currentTimeMillis(",
                                            "Clock.system",
                                            "JdbcTemplate",
                                            "JpaRepository",
                                            "EntityManager",
                                            "@Entity",
                                            ".save(",
                                            ".delete(");
                        });
    }

    private static Map<String, String> productionSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(EXECUTION_MAIN)) {
            for (Path path :
                    paths.filter(Files::isRegularFile)
                            .filter(candidate -> candidate.toString().endsWith(".java"))
                            .toList()) {
                sources.put(
                        EXECUTION_MAIN.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path));
            }
        }
        return sources;
    }
}
