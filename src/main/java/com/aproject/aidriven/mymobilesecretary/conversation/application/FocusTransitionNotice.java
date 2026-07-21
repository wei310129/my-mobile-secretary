package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.Objects;
import java.util.regex.Pattern;

/** Public, audience-safe facts for a persisted focus transition. */
public record FocusTransitionNotice(
        FocusTransitionType type,
        String previousSafeLabel,
        String currentSafeLabel,
        String activitySafeLabel) {

    private static final Pattern UUID_VALUE = Pattern.compile(
            "(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    private static final Pattern HMAC_DIGEST = Pattern.compile("(?i).*\\b[0-9a-f]{64}\\b.*");

    public FocusTransitionNotice {
        Objects.requireNonNull(type, "type");
        previousSafeLabel = safe(previousSafeLabel);
        currentSafeLabel = safe(currentSafeLabel);
        activitySafeLabel = safe(activitySafeLabel);
        requireLabels(type, previousSafeLabel, currentSafeLabel, activitySafeLabel);
    }

    public static FocusTransitionNotice forTransition(FocusTransitionType type,
                                                       String previousSafeLabel,
                                                       String currentSafeLabel,
                                                       String activitySafeLabel) {
        return new FocusTransitionNotice(type, previousSafeLabel, currentSafeLabel, activitySafeLabel);
    }

    private static void requireLabels(FocusTransitionType type, String previous,
                                      String current, String activity) {
        switch (type) {
            case ENTER, RESUME, EXIT, CLOSE, INVALIDATE -> required(current, type);
            case CHANGE_SUBFOCUS -> {
                required(current, type);
                required(activity, type);
            }
            case SWITCH -> {
                required(previous, type);
                required(current, type);
            }
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 120 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0 || UUID_VALUE.matcher(normalized).matches()
                || HMAC_DIGEST.matcher(normalized).matches()) {
            throw new IllegalArgumentException("focus notice requires an audience-safe label");
        }
        return normalized;
    }

    private static void required(String label, FocusTransitionType type) {
        if (label == null) {
            throw new IllegalArgumentException("focus notice label is required for " + type);
        }
    }
}
