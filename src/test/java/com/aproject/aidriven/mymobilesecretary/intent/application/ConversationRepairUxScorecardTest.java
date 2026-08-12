package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ConversationRepairUxScorecardTest {

    private static final Set<String> REQUIRED_OUTBOUND_FAMILIES = Set.of(
            "LINE_TEXT", "REPLAY", "IMAGE_OCR", "REST", "ERROR", "ROLE_DENIAL",
            "PROVIDER_REPLY", "FORMATTER", "GREETING", "FOCUS_NOTICE", "NOTIFICATION");

    @Test
    void everyAnonymizedHoldoutCaseMeetsEveryUxDimensionAndReferencesARegression()
            throws Exception {
        List<String> lines = new ClassPathResource("conversation-repair-ux-scorecard.txt")
                .getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(32);
        assertThat(lines.getFirst()).isEqualTo(
                "case_id|family|evidence|advances_request|truthful|natural|context_use|"
                        + "status_clarity|hard_gate|reason_below_5");

        Set<String> ids = new HashSet<>();
        Set<String> families = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split("\\|", -1);
            assertThat(columns).as("scorecard row: %s", line).hasSize(10);
            assertThat(ids.add(columns[0])).as("unique case id: %s", columns[0]).isTrue();
            families.add(columns[1]);
            verifyEvidence(columns[2]);

            boolean belowFive = false;
            for (int index = 3; index <= 7; index++) {
                int score = Integer.parseInt(columns[index]);
                assertThat(score).as("%s dimension %s", columns[0], index)
                        .isBetween(4, 5);
                belowFive |= score < 5;
            }
            assertThat(columns[8]).isEqualTo("PASS");
            if (belowFive) {
                assertThat(columns[9]).isNotBlank().isNotEqualTo("NONE");
            }
        }
        assertThat(families).containsAll(REQUIRED_OUTBOUND_FAMILIES);
    }

    private static void verifyEvidence(String evidence) throws java.io.IOException {
        String[] reference = evidence.split("#", -1);
        assertThat(reference).as("evidence: %s", evidence).hasSize(2);
        String relative = reference[0]
                .replaceFirst("^com\\.aproject\\.aidriven\\.mymobilesecretary\\.", "")
                .replace('.', '/') + ".java";
        Path source = Path.of("src/test/java/com/aproject/aidriven/mymobilesecretary")
                .resolve(relative);
        assertThat(source).exists().isRegularFile();
        String method = "\\bvoid\\s+" + Pattern.quote(reference[1]) + "\\s*\\(";
        assertThat(Files.readString(source, StandardCharsets.UTF_8)).containsPattern(method);
    }
}
