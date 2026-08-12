package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService.PersonalStartReminderState;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CalendarStartReminderLifecycleServiceTest {

    private static final UUID PLAN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private CalendarReminderApplicationService reminders;
    private CalendarStartReminderLifecycleService service;

    @BeforeEach
    void setUp() {
        reminders = mock(CalendarReminderApplicationService.class);
        service = new CalendarStartReminderLifecycleService(reminders);
    }

    @Test
    void missingReminderAsksOneTypedDepartureQuestion() {
        when(reminders.personalStartReminderState(PLAN_ID, "start"))
                .thenReturn(PersonalStartReminderState.NONE);

        var decision = service.afterStartTimeCreated(
                PLAN_ID,
                "start",
                CalendarStartReminderLifecycleService.PublicKind.DEPARTURE);

        assertThat(decision.action())
                .isEqualTo(CalendarStartReminderLifecycleService.Action.ASK);
        assertThat(decision.message()).isEqualTo("要設定出發提醒嗎？");
    }

    @Test
    void relativeReminderFollowsAdjustedStartAndProducesAUserNotice() {
        when(reminders.personalStartReminderState(PLAN_ID, "start"))
                .thenReturn(PersonalStartReminderState.RELATIVE_ACTIVE);

        var decision = service.beforeStartTimeAdjusted(
                PLAN_ID,
                "start",
                CalendarStartReminderLifecycleService.PublicKind.DEPARTURE);

        assertThat(decision.action())
                .isEqualTo(CalendarStartReminderLifecycleService.Action.RETAINED_RELATIVE);
        assertThat(decision.message())
                .isEqualTo("原本的出發提醒規則會配合新時間主動提醒您。");
    }

    @Test
    void acceptingCreatesOnlyTypedAdaptiveRelativeReminders() {
        when(reminders.createAdaptiveDepartureReminders(
                        PLAN_ID,
                        "start",
                        Duration.ofMinutes(42),
                        CalendarReminderDeliveryMode.ONCE,
                        null))
                .thenReturn(List.of(mock(), mock()));

        assertThat(service.createAdaptiveDepartureReminder(
                        PLAN_ID, "start", Duration.ofMinutes(42)))
                .isEqualTo(2);
        verify(reminders).createAdaptiveDepartureReminders(
                PLAN_ID,
                "start",
                Duration.ofMinutes(42),
                CalendarReminderDeliveryMode.ONCE,
                null);
    }

    @Test
    void explicitLeadCreatesOnlyOneTypedRelativeReminder() {
        service.createExplicitDepartureReminder(PLAN_ID, "start", 5);

        verify(reminders).createPersonalRelativeForPlanNode(
                PLAN_ID, "start", Duration.ofMinutes(-5));
    }

    @Test
    void answeringAReviewedFixedReminderClearsOnlyThatTypedStartScope() {
        when(reminders.cancelPersonalStartRemindersRequiringReview(PLAN_ID, "start"))
                .thenReturn(1);

        assertThat(service.clearReviewedStartReminders(PLAN_ID, "start")).isEqualTo(1);

        verify(reminders).cancelPersonalStartRemindersRequiringReview(PLAN_ID, "start");
    }
}
