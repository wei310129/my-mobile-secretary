package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.util.List;

public record CalendarReminderRules(List<CalendarReminderRule> rules) {

    private static final int MAX_ACTIVE_RULES = 8;

    public CalendarReminderRules {
        rules = List.copyOf(rules);
        if (rules.size() > MAX_ACTIVE_RULES) {
            throw new IllegalArgumentException("At most eight active reminder rules are allowed");
        }
        if (rules.stream().distinct().count() != rules.size()) {
            throw new IllegalArgumentException("Duplicate reminder rules are not allowed");
        }
    }

    public static CalendarReminderRules of(List<CalendarReminderRule> rules) {
        return new CalendarReminderRules(rules);
    }
}
