package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarEditorMutationResult(
        UUID mutationId, UUID nodeId, String label, long nodeRevision) {}
