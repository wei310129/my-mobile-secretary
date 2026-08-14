package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import java.util.Objects;

/** A verified node and its optional typed location, never raw place text. */
public record CalendarNodeDraft(CalendarTimeNode node, CalendarLocation location) {

    public CalendarNodeDraft {
        Objects.requireNonNull(node, "node");
    }

    public static CalendarNodeDraft of(CalendarTimeNode node) {
        return new CalendarNodeDraft(node, null);
    }

    public static CalendarNodeDraft at(CalendarTimeNode node, CalendarLocation location) {
        return new CalendarNodeDraft(node, Objects.requireNonNull(location, "location"));
    }
}
