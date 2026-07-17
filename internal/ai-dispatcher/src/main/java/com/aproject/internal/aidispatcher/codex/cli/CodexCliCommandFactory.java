package com.aproject.internal.aidispatcher.codex.cli;

import com.aproject.internal.aidispatcher.codex.CodexStartCommand;
import com.aproject.internal.aidispatcher.config.CliCodexProperties;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class CodexCliCommandFactory {

    private final CliCodexProperties properties;
    private final CodexPromptRenderer promptRenderer;

    CodexCliCommandFactory(CliCodexProperties properties, CodexPromptRenderer promptRenderer) {
        this.properties = properties;
        this.promptRenderer = promptRenderer;
    }

    CodexCliProcessSpec create(CodexStartCommand startCommand) {
        ConfiguredPaths paths = configuredPaths();
        Path executable = paths.executable();
        Path repository = paths.repository();
        String prompt = promptRenderer.render(startCommand);
        if (prompt.length() > properties.maximumPromptCharacters()) {
            throw new IllegalArgumentException("Rendered Codex prompt exceeds configured limit");
        }
        return new CodexCliProcessSpec(
                List.of(
                        executable.toString(),
                        "--cd", repository.toString(),
                        "--sandbox", "workspace-write",
                        "--ask-for-approval", "never",
                        "--config", "sandbox_workspace_write.network_access=false",
                        "--config", "shell_environment_policy.include_only=[\"PATH\",\"HOME\",\"USERPROFILE\",\"TEMP\",\"TMP\",\"SystemRoot\",\"ComSpec\"]",
                        "exec", "--json", "resume", startCommand.externalSessionId(), "-"),
                repository,
                prompt);
    }

    void validateConfiguration() {
        configuredPaths();
    }

    private ConfiguredPaths configuredPaths() {
        Path executable = properties.executablePath();
        Path repository = properties.repositoryPath();
        requireExecutable(executable);
        requireGitRepository(repository);
        return new ConfiguredPaths(
                realPath(executable, "Codex executable"),
                realPath(repository, "Codex repository"));
    }

    private static void requireExecutable(Path executable) {
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException(
                    "Configured Codex executable is not a regular file: " + executable);
        }
    }

    private static void requireGitRepository(Path repository) {
        if (!Files.isDirectory(repository) || !Files.exists(repository.resolve(".git"))) {
            throw new IllegalArgumentException(
                    "Configured Codex repository is not a Git working tree: " + repository);
        }
    }

    private static Path realPath(Path path, String description) {
        try {
            return path.toRealPath();
        } catch (IOException resolutionFailure) {
            throw new IllegalArgumentException(
                    description + " could not be resolved: " + path, resolutionFailure);
        }
    }

    private record ConfiguredPaths(Path executable, Path repository) {
    }
}
