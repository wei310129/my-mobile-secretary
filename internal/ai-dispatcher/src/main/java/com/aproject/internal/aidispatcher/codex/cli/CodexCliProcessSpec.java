package com.aproject.internal.aidispatcher.codex.cli;

import java.nio.file.Path;
import java.util.List;

record CodexCliProcessSpec(
        List<String> command,
        Path workingDirectory,
        String standardInput
) {
    CodexCliProcessSpec {
        command = List.copyOf(command);
        if (command.isEmpty() || workingDirectory == null
                || standardInput == null || standardInput.isBlank()) {
            throw new IllegalArgumentException(
                    "command, workingDirectory and standardInput are required");
        }
    }
}
