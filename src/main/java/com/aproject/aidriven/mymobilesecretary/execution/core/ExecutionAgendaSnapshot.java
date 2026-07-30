package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.util.List;
import java.util.Objects;

public record ExecutionAgendaSnapshot(
        List<ExecutionTaskItem> tasks, List<ExecutionCalendarItem> calendarItems) {

    public ExecutionAgendaSnapshot {
        tasks = immutableNonNullItems(tasks, "tasks");
        calendarItems = immutableNonNullItems(calendarItems, "calendarItems");
    }

    private static <T> List<T> immutableNonNullItems(List<T> items, String field) {
        Objects.requireNonNull(items, field);
        var copy = List.copyOf(items);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        return copy;
    }
}
