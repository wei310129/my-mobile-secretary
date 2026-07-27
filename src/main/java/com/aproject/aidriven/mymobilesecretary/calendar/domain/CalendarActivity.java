package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CalendarActivity {

    private final String title;
    private final CalendarPlacement placement;
    private final List<CalendarTimeNode> nodes = new ArrayList<>();

    CalendarActivity(String title, CalendarPlacement placement) {
        this.title = requireTitle(title);
        this.placement = Objects.requireNonNull(placement, "placement");
    }

    public String title() {
        return title;
    }

    public CalendarPlacement placement() {
        return placement;
    }

    public void addNode(CalendarTimeNode node) {
        CalendarNodeCollection.add(nodes, node);
    }

    public List<CalendarTimeNode> nodes() {
        return List.copyOf(nodes);
    }

    public CalendarActivity addActivity(String ignoredTitle) {
        throw new IllegalStateException("Calendar activities cannot contain child activities");
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        return title.trim();
    }
}
