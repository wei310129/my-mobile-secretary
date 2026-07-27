package com.aproject.aidriven.mymobilesecretary.calendar.query;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;

public record CalendarQueryItem(
        String title,
        CalendarPlacement placement,
        String category,
        String onlineLinkHost) {}
