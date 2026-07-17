package com.aproject.aidriven.mymobilesecretary.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Prevents future request data from being routed through unreviewed dynamic SQL. */
class DatabaseAccessSafetyArchitectureTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Set<String> REVIEWED_LOW_LEVEL_ACCESS = Set.of(
            "com/aproject/aidriven/mymobilesecretary/account/security/idempotency/IdempotencyService.java",
            "com/aproject/aidriven/mymobilesecretary/account/workspace/DatabaseRoleSafetyVerifier.java",
            "com/aproject/aidriven/mymobilesecretary/account/workspace/WorkspaceRlsJpaDialect.java",
            "com/aproject/aidriven/mymobilesecretary/integration/notification/NotificationPublisher.java");
    private static final Set<String> REVIEWED_NATIVE_QUERIES = Set.of(
            "com/aproject/aidriven/mymobilesecretary/geo/persistence/GeofenceRuleRepository.java",
            "com/aproject/aidriven/mymobilesecretary/geo/persistence/PlaceRepository.java",
            "com/aproject/aidriven/mymobilesecretary/travel/persistence/TravelItineraryDraftRepository.java");

    @Test
    void lowLevelDatabaseAccessStaysInsideReviewedParameterBoundFiles() throws IOException {
        Map<String, String> sources = productionSources();

        assertThat(pathsContaining(sources, "JdbcTemplate", "prepareStatement("))
                .as("Use Spring Data JPA by default; low-level SQL requires a security review")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_LOW_LEVEL_ACCESS);
        assertThat(pathsContaining(sources, "nativeQuery = true"))
                .as("Native queries are exceptions and must use named parameters")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_NATIVE_QUERIES);
    }

    @Test
    void unsafeStatementAndJpaEscapeHatchesAreForbidden() throws IOException {
        Map<String, String> sources = productionSources();

        assertThat(pathsContaining(sources, "createStatement(", "JpaSort.unsafe(",
                "createNativeQuery("))
                .as("Do not concatenate request data into executable SQL")
                .isEmpty();
    }

    @Test
    void reviewedNativeQueriesBindEveryRuntimeValue() throws IOException {
        Map<String, String> sources = productionSources();

        for (String path : REVIEWED_NATIVE_QUERIES) {
            String source = sources.get(path);
            assertThat(source).as(path).contains("@Param(");
            assertThat(source).as(path).doesNotContain("#{");
        }
    }

    private static Set<String> pathsContaining(Map<String, String> sources, String... needles) {
        java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        sources.forEach((path, source) -> {
            for (String needle : needles) {
                if (source.contains(needle)) {
                    matches.add(path);
                    break;
                }
            }
        });
        return matches;
    }

    private static Map<String, String> productionSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN_JAVA)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String relative = MAIN_JAVA.relativize(path).toString().replace('\\', '/');
                if (!relative.contains("/internal/")) {
                    sources.put(relative, Files.readString(path));
                }
            }
        }
        return sources;
    }
}
