package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntentScriptSafetyPolicyTest {

    @Test
    void teacherNoticeWithoutEndTimeCannotBecomeInventedSchedule() {
        IntentScript raw = script(command(IntentCommand.Type.CREATE_SCHEDULE,
                "父親節活動", "2026-07-19T10:00:00+08:00",
                "2026-07-19T11:00:00+08:00", null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "老師通知明天十點報到，沒有說幾點結束", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().reason()).contains("缺活動結束時間", "不會自行補一小時");
    }

    @Test
    void schoolClassRangeCannotInventDropOffPickupDurations() {
        IntentScript raw = script(
                command(IntentCommand.Type.CREATE_SCHEDULE, "送女兒上課",
                        "2026-07-19T09:40:00+08:00", "2026-07-19T10:00:00+08:00", null),
                command(IntentCommand.Type.CREATE_SCHEDULE, "接女兒下課",
                        "2026-07-19T12:00:00+08:00", "2026-07-19T12:20:00+08:00", null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天送女兒上英文課十點到十二點", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().reason()).contains("誰送", "誰接", "不會建立行程");
    }

    @Test
    void scheduleReminderAlwaysCarriesTheExplicitLeadMinutes() {
        IntentScript raw = script(command(IntentCommand.Type.CREATE_TASK,
                "提醒專案會議", null, null, null));

        IntentScript safe = IntentScriptSafetyPolicy.apply("專案會議前二十分鐘提醒我", raw);

        assertThat(safe.commands()).hasSize(1);
        assertThat(safe.commands().getFirst().type())
                .isEqualTo(IntentCommand.Type.ADD_SCHEDULE_REMINDER);
        assertThat(safe.commands().getFirst().title()).isEqualTo("專案會議");
        assertThat(safe.commands().getFirst().safeOptions().leadMinutes()).isEqualTo(20);
    }

    @Test
    void newScheduleKeepsCreationBeforeItsReminder() {
        IntentScript raw = script(command(IntentCommand.Type.CREATE_SCHEDULE,
                "產品會議", "2026-07-19T14:00:00+08:00",
                "2026-07-19T15:00:00+08:00", null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天下午兩點產品會議，會議前半小時提醒我", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.ADD_SCHEDULE_REMINDER);
        assertThat(safe.commands().getLast().title()).isEqualTo("產品會議");
        assertThat(safe.commands().getLast().safeOptions().leadMinutes()).isEqualTo(30);
    }

    private static IntentScript script(IntentCommand... commands) {
        return new IntentScript(List.of(commands));
    }

    private static IntentCommand command(IntentCommand.Type type, String title,
                                         String startAt, String endAt,
                                         IntentOptions options) {
        return new IntentCommand(type, title, null, startAt, endAt, null, null, null,
                null, null, null, null, null, options);
    }
}
