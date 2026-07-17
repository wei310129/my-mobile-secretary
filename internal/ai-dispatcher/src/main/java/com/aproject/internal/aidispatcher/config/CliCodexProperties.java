package com.aproject.internal.aidispatcher.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.codex-cli")
public record CliCodexProperties(
        boolean enabled,
        String executable,
        String repository,
        Duration heartbeatInterval,
        Duration noProgressTimeout,
        Duration maximumRuntime,
        int maximumJsonLineLength,
        int maximumStderrCharacters,
        int maximumPromptCharacters
) {

    public CliCodexProperties {
        executable = normalized(executable);
        repository = normalized(repository);
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(noProgressTimeout, "noProgressTimeout");
        requirePositive(maximumRuntime, "maximumRuntime");
        requirePositive(maximumJsonLineLength, "maximumJsonLineLength");
        requirePositive(maximumStderrCharacters, "maximumStderrCharacters");
        requirePositive(maximumPromptCharacters, "maximumPromptCharacters");
        if (heartbeatInterval.compareTo(noProgressTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeatInterval must be shorter than noProgressTimeout");
        }
        if (noProgressTimeout.compareTo(maximumRuntime) >= 0) {
            throw new IllegalArgumentException(
                    "noProgressTimeout must be shorter than maximumRuntime");
        }
        if (enabled && (executable.isEmpty() || repository.isEmpty())) {
            throw new IllegalArgumentException(
                    "codex-cli executable and repository are required when enabled");
        }
    }

    public Path executablePath() {
        return requireAbsolutePath(executable, "executable");
    }

    public Path repositoryPath() {
        return requireAbsolutePath(repository, "repository");
    }

    private static Path requireAbsolutePath(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("codex-cli " + name + " is not configured");
        }
        Path path = Path.of(value).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("codex-cli " + name + " must be an absolute path");
        }
        return path;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
