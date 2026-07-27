package com.aproject.aidriven.mymobilesecretary.calendar.query;

import java.util.List;

public record CalendarQueryPage(List<CalendarQueryItem> items, Integer nextOffset) {

    public CalendarQueryPage {
        items = List.copyOf(items);
    }
}
