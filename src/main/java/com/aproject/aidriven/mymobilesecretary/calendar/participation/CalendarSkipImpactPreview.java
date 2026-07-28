package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;
import java.util.UUID;

public record CalendarSkipImpactPreview(
        String confirmationToken,
        String digest,
        long sourceRevision,
        boolean strongConfirmationRequired,
        List<UUID> affectedNodeIds,
        List<UUID> affectedReminderIds,
        List<UUID> retainedLinkedItemIds) {}
