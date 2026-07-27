package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Map;
import java.util.UUID;

public record CalendarShareLastAccessResolution(
        Action action,
        Map<UUID, Long> expectedRegistrationRevisions,
        String removalReason) {

    public enum Action {
        PRESERVE_MINIMUM_ACCESS,
        REMOVE_PARTICIPANT
    }

    public CalendarShareLastAccessResolution {
        expectedRegistrationRevisions =
                expectedRegistrationRevisions == null
                        ? Map.of()
                        : Map.copyOf(expectedRegistrationRevisions);
    }

    public static CalendarShareLastAccessResolution preserve() {
        return new CalendarShareLastAccessResolution(
                Action.PRESERVE_MINIMUM_ACCESS, Map.of(), null);
    }

    public static CalendarShareLastAccessResolution removeParticipant(
            UUID registrationId,
            long expectedRevision,
            String reason) {
        return new CalendarShareLastAccessResolution(
                Action.REMOVE_PARTICIPANT,
                Map.of(registrationId, expectedRevision),
                reason);
    }
}
