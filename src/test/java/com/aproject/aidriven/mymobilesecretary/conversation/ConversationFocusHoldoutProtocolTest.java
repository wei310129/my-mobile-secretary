package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationFocusHoldoutProtocolTest {

    @TempDir Path tempDirectory;

    @Test
    void captureRejectsAnOracleBeforeTheSystemRuns() throws Exception {
        Path input = Files.writeString(tempDirectory.resolve("input.json"), "{}");
        Properties properties = properties("capture", input, tempDirectory.resolve("capture.json"));
        properties.setProperty("conversation.focus.holdout.oracle", tempDirectory.resolve("oracle.json").toString());

        assertThatThrownBy(() -> ConversationFocusHoldoutProtocol.configuration(properties,
                Path.of(System.getProperty("user.dir"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capture must not receive an oracle");
    }

    @Test
    void assertRejectsInputBecauseItMustNotRunTheSystemAgain() throws Exception {
        Path capture = Files.writeString(tempDirectory.resolve("capture.json"), "{}");
        Path oracle = Files.writeString(tempDirectory.resolve("oracle.json"), "{}");
        Properties properties = properties("assert", capture, null);
        properties.setProperty("conversation.focus.holdout.oracle", oracle.toString());
        properties.setProperty("conversation.focus.holdout.input", tempDirectory.resolve("input.json").toString());

        assertThatThrownBy(() -> ConversationFocusHoldoutProtocol.configuration(properties,
                Path.of(System.getProperty("user.dir"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assert must not receive an input");
    }

    @Test
    void captureRejectsARepositoryLocalOrExistingArtifactPath() throws Exception {
        Path input = Files.writeString(tempDirectory.resolve("input.json"), "{}");
        Path repositoryCapture = Path.of(System.getProperty("user.dir"), "target", "capture.json");
        Properties repositoryProperties = properties("capture", input, repositoryCapture);

        assertThatThrownBy(() -> ConversationFocusHoldoutProtocol.configuration(repositoryProperties,
                Path.of(System.getProperty("user.dir"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capture path must be outside the repository");

        Path existingCapture = Files.writeString(tempDirectory.resolve("existing.json"), "{}");
        Properties existingProperties = properties("capture", input, existingCapture);

        assertThatThrownBy(() -> ConversationFocusHoldoutProtocol.configuration(existingProperties,
                Path.of(System.getProperty("user.dir"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capture path must not already exist");
    }

    private static Properties properties(String phase, Path inputOrCapture, Path capture) {
        Properties properties = new Properties();
        properties.setProperty("conversation.focus.holdout.phase", phase);
        if ("capture".equals(phase)) {
            properties.setProperty("conversation.focus.holdout.input", inputOrCapture.toString());
            properties.setProperty("conversation.focus.holdout.capture", capture.toString());
        } else {
            properties.setProperty("conversation.focus.holdout.capture", inputOrCapture.toString());
        }
        return properties;
    }
}
