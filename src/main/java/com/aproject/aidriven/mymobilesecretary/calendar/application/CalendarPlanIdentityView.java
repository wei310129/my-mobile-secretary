package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import java.util.UUID;

public record CalendarPlanIdentityView(
        UUID planId,
        String title,
        String category,
        String onlineHost,
        CalendarPlanStatus status,
        long revision) {
}
