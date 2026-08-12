package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.List;
import java.util.Objects;

/** Determines whether verified route semantics describe a trip or an activity with transport. */
enum RouteJourneyKind {
    STANDALONE_TRIP,
    ACTIVITY_WITH_TRANSPORT;

    private static final List<String> EXPLICIT_ACTIVITY_MARKERS = List.of(
            "參加", "出席", "報到", "開會", "上課", "看診", "就醫", "用餐", "聚餐",
            "拜訪", "面試", "辦理", "洽公", "觀賞", "看電影", "比賽", "演出");

    static RouteJourneyKind resolve(IntentCommand command, CalendarPlacement placement) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(placement, "placement");
        if (!(placement instanceof CalendarPlacement.TimedPoint)) {
            return ACTIVITY_WITH_TRANSPORT;
        }
        String source = command.sourceText();
        String semanticText = source == null || source.isBlank() ? command.title() : source;
        if (semanticText != null
                && EXPLICIT_ACTIVITY_MARKERS.stream().anyMatch(semanticText::contains)) {
            return ACTIVITY_WITH_TRANSPORT;
        }
        return STANDALONE_TRIP;
    }
}
