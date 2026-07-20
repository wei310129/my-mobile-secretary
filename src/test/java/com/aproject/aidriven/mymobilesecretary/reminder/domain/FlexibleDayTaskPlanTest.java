package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FlexibleDayTaskPlanTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:00:00Z");

    @Test
    void reminderAtExactlyFiveMinutesIsAcceptedAndEarlierIsRejected() {
        FlexibleDayTaskPlan accepted = FlexibleDayTaskPlan.schedule(
                7L, LocalDate.of(2026, 7, 19), NOW.plusSeconds(300),
                FlexibleDayTaskPlan.SourceKind.USER_REQUEST, NOW);

        assertThat(accepted.getRemindAt()).isEqualTo(NOW.plusSeconds(300));
        assertThatThrownBy(() -> FlexibleDayTaskPlan.schedule(
                8L, LocalDate.of(2026, 7, 19), NOW.plusSeconds(299),
                FlexibleDayTaskPlan.SourceKind.USER_REQUEST, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("five minutes");
    }

    @Test
    void completionClosesPlanSoNoFutureReminderCanBeConsideredActive() {
        FlexibleDayTaskPlan plan = FlexibleDayTaskPlan.schedule(
                7L, LocalDate.of(2026, 7, 20), NOW.plusSeconds(3600),
                FlexibleDayTaskPlan.SourceKind.PAYMENT_NOTICE, NOW);

        plan.complete(NOW.plusSeconds(60));

        assertThat(plan.getStatus()).isEqualTo(FlexibleDayTaskPlan.Status.COMPLETED);
    }
}
