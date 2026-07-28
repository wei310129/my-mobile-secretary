package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarEditorActivityMutationResult(
        UUID mutationId,
        UUID activityId,
        CalendarEditorActivityChange.Field field,
        String value,
        long activityVersion) {}
