package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderPreferenceService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReminderIntentHandlerTest {

    private ReminderIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReminderIntentHandler(
                mock(TaskService.class),
                mock(ScheduleService.class),
                mock(ConversationContextService.class),
                mock(ReminderPreferenceService.class),
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void registersEveryReminderType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.ADD_SCHEDULE_REMINDER,
                IntentCommand.Type.ASK_SCHEDULE_REMINDER,
                IntentCommand.Type.SET_QUIET_HOURS,
                IntentCommand.Type.CLEAR_QUIET_HOURS,
                IntentCommand.Type.MUTE_REMINDERS,
                IntentCommand.Type.RESUME_REMINDERS,
                IntentCommand.Type.ASK_REMINDER_PREFERENCES));
    }

    @Test
    void noPreferenceKeepsExistingReply() {
        IntentResult result = handler.handle(
                "提醒設定", command(IntentCommand.Type.ASK_REMINDER_PREFERENCES));

        assertThat(result.action()).isEqualTo(IntentResult.Action.REMINDER_PREFERENCE_INFO);
        assertThat(result.message()).isEqualTo(IntentResult.message(
                IntentResult.Action.REMINDER_PREFERENCE_INFO,
                "目前沒有固定勿擾或臨時靜音設定。 ").message());
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
