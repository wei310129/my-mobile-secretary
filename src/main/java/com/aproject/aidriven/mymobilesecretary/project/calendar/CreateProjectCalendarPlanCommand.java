package com.aproject.aidriven.mymobilesecretary.project.calendar;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import java.util.Objects;

public record CreateProjectCalendarPlanCommand(
        String requestKey, CreateCalendarPlanCommand calendar) {

    public CreateProjectCalendarPlanCommand {
        if (requestKey == null
                || requestKey.isBlank()
                || requestKey.strip().length() > 160) {
            throw new IllegalArgumentException(
                    "A bounded Project Calendar request key is required");
        }
        requestKey = requestKey.strip();
        Objects.requireNonNull(calendar, "calendar command is required");
    }
}
