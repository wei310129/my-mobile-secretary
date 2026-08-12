package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ClarificationCallSiteInventoryTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src/main/java/com/aproject/aidriven/mymobilesecretary");

    @Test
    void everyClarificationOccurrenceHasAReviewedConversationStrategy() throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (String line : new ClassPathResource("conversation-clarification-inventory.txt")
                .getContentAsString(StandardCharsets.UTF_8).lines().toList()) {
            if (line.isBlank()) continue;
            String[] columns = line.split("\\|", -1);
            assertThat(columns).as("inventory: %s", line).hasSize(7);
            assertThat(columns).allMatch(value -> !value.isBlank());
            expected.put(columns[0], Integer.parseInt(columns[1]));
        }

        Map<String, Integer> actual = new LinkedHashMap<>();
        try (var paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> count(path, actual));
        }

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(actual.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(expected.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static void count(Path path, Map<String, Integer> actual) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            int occurrences = source.split("clarificationNeeded", -1).length - 1;
            if (occurrences > 0) {
                String relative = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                actual.put(relative, occurrences);
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot inspect clarification source", failure);
        }
    }
}
