package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.util.List;
import java.util.Objects;

public record CreateCalendarPlanCommand(
        String requestKey,
        String title,
        CalendarPlacement placement,
        String category,
        String onlineLink,
        String onlineLinkLabel,
        List<CalendarActivityDraft> activities,
        List<CalendarNodeDraft> nodes) {

    public CreateCalendarPlanCommand {
        if (title == null || title.isBlank() || title.strip().length() > 200) {
            throw new IllegalArgumentException(
                    "Calendar title must contain 1 to 200 characters");
        }
        title = title.strip();
        Objects.requireNonNull(placement, "placement");
        activities = List.copyOf(activities == null ? List.of() : activities);
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }
}
