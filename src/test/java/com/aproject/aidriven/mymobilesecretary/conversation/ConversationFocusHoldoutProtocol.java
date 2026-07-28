package com.aproject.aidriven.mymobilesecretary.conversation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Fail-closed configuration boundary for the evaluator-owned Focus holdout.
 *
 * <p>The capture artifact is intentionally outside the repository. This class never accepts fixture contents or
 * oracle values from the build configuration, so the test report cannot disclose them.</p>
 */
final class ConversationFocusHoldoutProtocol {

    static final String PHASE = "conversation.focus.holdout.phase";
    static final String INPUT = "conversation.focus.holdout.input";
    static final String CAPTURE = "conversation.focus.holdout.capture";
    static final String ORACLE = "conversation.focus.holdout.oracle";

    private ConversationFocusHoldoutProtocol() {}

    static Configuration configuration(Properties properties, Path repositoryRoot) {
        Path normalizedRepositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        Phase phase = Phase.parse(required(properties, PHASE));
        Path capture = path(properties, CAPTURE);
        if (phase == Phase.CAPTURE) {
            rejectIfPresent(properties, ORACLE, "capture must not receive an oracle");
            Path input = requiredRegularFile(properties, INPUT);
            requireExternalNewCapture(capture, normalizedRepositoryRoot);
            return new Configuration(phase, input, capture, null);
        }

        rejectIfPresent(properties, INPUT, "assert must not receive an input");
        requireRegularFile(capture, CAPTURE);
        Path oracle = requiredRegularFile(properties, ORACLE);
        return new Configuration(phase, null, capture, oracle);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static Path path(Properties properties, String key) {
        return Path.of(required(properties, key)).toAbsolutePath().normalize();
    }

    private static Path requiredRegularFile(Properties properties, String key) {
        Path path = path(properties, key);
        requireRegularFile(path, key);
        return path;
    }

    private static void requireRegularFile(Path path, String key) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(key + " must name a readable regular file");
        }
    }

    private static void rejectIfPresent(Properties properties, String key, String message) {
        String value = properties.getProperty(key);
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireExternalNewCapture(Path capture, Path repositoryRoot) {
        if (capture.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("capture path must be outside the repository");
        }
        if (Files.exists(capture)) {
            throw new IllegalArgumentException("capture path must not already exist");
        }
        Path parent = capture.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("capture parent directory must already exist");
        }
    }

    enum Phase {
        CAPTURE,
        ASSERT;

        static Phase parse(String value) {
            return switch (value) {
                case "capture" -> CAPTURE;
                case "assert" -> ASSERT;
                default -> throw new IllegalArgumentException("holdout phase must be capture or assert");
            };
        }
    }

    record Configuration(Phase phase, Path input, Path capture, Path oracle) {}
}
