package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.util.UUID;

public record KnowledgeMaterializationView(
        UUID id,
        CalendarKnowledgeBindingView.SourceKind sourceKind,
        UUID sourceBindingId,
        TargetKind targetKind,
        String resultReference) {

    public enum TargetKind {
        TASK,
        CALENDAR_NODE,
        PLANNING_PREFERENCE,
        BUFFER_RULE,
        TASK_REMINDER,
        CALENDAR_REMINDER
    }
}
