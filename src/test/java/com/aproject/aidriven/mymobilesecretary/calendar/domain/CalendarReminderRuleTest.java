package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarReminderRuleTest {

    @Test
    void resolvesNodeTimeAndRelativeRemindersFromTheProductExample() {
        var nodeTime = Instant.parse("2026-07-25T03:20:00Z");
        var rules = List.of(CalendarReminderRule.atNode(), CalendarReminderRule.before(Duration.ofMinutes(15)));

        assertThat(rules).extracting(rule -> rule.resolve(nodeTime))
                .containsExactly(nodeTime, Instant.parse("2026-07-25T03:05:00Z"));
    }

    @Test
    void rejectsAfterNodeOffsetAndMoreThanEightActiveRules() {
        assertThatThrownBy(() -> CalendarReminderRule.offset(Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarReminderRules.of(List.of(
                CalendarReminderRule.atNode(), CalendarReminderRule.before(Duration.ofMinutes(1)),
                CalendarReminderRule.before(Duration.ofMinutes(2)), CalendarReminderRule.before(Duration.ofMinutes(3)),
                CalendarReminderRule.before(Duration.ofMinutes(4)), CalendarReminderRule.before(Duration.ofMinutes(5)),
                CalendarReminderRule.before(Duration.ofMinutes(6)), CalendarReminderRule.before(Duration.ofMinutes(7)),
                CalendarReminderRule.before(Duration.ofMinutes(8)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
