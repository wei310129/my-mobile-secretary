package com.aproject.internal.aidispatcher.codex.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.internal.aidispatcher.codex.CodexEventReference;
import com.aproject.internal.aidispatcher.codex.CodexStartCommand;
import com.aproject.internal.aidispatcher.config.CliCodexProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliContractTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void commandIsFixedAndPromptOnlyUsesStdin() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("codex.exe"));
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        CodexCliCommandFactory factory = new CodexCliCommandFactory(
                properties(executable, repository), new CodexPromptRenderer(new ObjectMapper()));

        CodexCliProcessSpec spec = factory.create(startCommand());

        assertThat(spec.command()).containsExactly(
                executable.toString(), "--cd", repository.toString(),
                "--sandbox", "workspace-write", "--ask-for-approval", "never",
                "--config", "sandbox_workspace_write.network_access=false",
                "--config", "shell_environment_policy.include_only=[\"PATH\",\"HOME\",\"USERPROFILE\",\"TEMP\",\"TMP\",\"SystemRoot\",\"ComSpec\"]",
                "exec", "--json", "resume",
                "0199a213-81c0-7800-8aa1-bbab2a035a53", "-");
        assertThat(spec.command()).doesNotContain("--last", "danger-full-access");
        assertThat(spec.command()).noneMatch(value -> value.contains("修正登入錯誤"));
        assertThat(spec.standardInput()).contains("修正登入錯誤");
        assertThat(spec.workingDirectory()).isEqualTo(repository);
    }

    @Test
    void parserCapturesTerminalUsageAndIgnoresUnknownEvents() {
        CodexJsonlParser parser = new CodexJsonlParser(new ObjectMapper(), 10_000);
        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"session-1\"}");
        parser.accept("{\"type\":\"turn.started\"}");
        parser.accept("{\"type\":\"future.event\",\"new_field\":true}");
        parser.accept("{\"type\":\"turn.completed\",\"usage\":{" +
                "\"input_tokens\":120,\"cached_input_tokens\":20," +
                "\"output_tokens\":30,\"reasoning_output_tokens\":10}}");

        CodexCliProgress progress = parser.snapshot();
        assertThat(progress.threadStarted()).isTrue();
        assertThat(progress.turnStarted()).isTrue();
        assertThat(progress.turnCompleted()).isTrue();
        assertThat(progress.reportedThreadId()).isEqualTo("session-1");
        assertThat(progress.inputTokens()).isEqualTo(120);
        assertThat(progress.cachedInputTokens()).isEqualTo(20);
        assertThat(progress.outputTokens()).isEqualTo(30);
        assertThat(progress.reasoningOutputTokens()).isEqualTo(10);
    }

    @Test
    void malformedOrOversizedJsonlFailsClosed() {
        CodexJsonlParser parser = new CodexJsonlParser(new ObjectMapper(), 20);
        assertThatThrownBy(() -> parser.accept("not-json"))
                .isInstanceOf(CodexCliProtocolException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> parser.accept("x".repeat(21)))
                .isInstanceOf(CodexCliProtocolException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void enabledConfigurationRequiresAbsolutePaths() {
        CliCodexProperties properties = new CliCodexProperties(
                true, "codex.exe", "repository",
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofHours(2),
                1000, 1000, 1000);
        assertThatThrownBy(properties::executablePath)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute path");
    }

    private CliCodexProperties properties(Path executable, Path repository) {
        return new CliCodexProperties(
                true, executable.toString(), repository.toString(),
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofHours(2),
                1_048_576, 16_384, 1_048_576);
    }

    private CodexStartCommand startCommand() {
        return new CodexStartCommand(
                UUID.fromString("b0b36ca9-dc48-4d24-b658-4a41f1ef0b5d"), 7,
                "development-main", "開發主要對話", "CODEX_DESKTOP",
                "0199a213-81c0-7800-8aa1-bbab2a035a53", 3,
                List.of(new CodexEventReference(
                        "main-conversation-feed-v1", "event-1",
                        "line.conversation.recorded", "line-message:1", 1,
                        Instant.parse("2026-07-17T00:00:00Z"),
                        "{\"text\":\"修正登入錯誤\"}")));
    }
}
