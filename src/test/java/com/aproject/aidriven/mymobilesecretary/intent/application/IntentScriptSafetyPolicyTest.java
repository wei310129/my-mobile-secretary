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
    void caregivingTransportBecomesPointRemindersInsteadOfClassIntervals() {
        IntentScript raw = script(
                command(IntentCommand.Type.CREATE_SCHEDULE, "送女兒上課",
                        "2026-07-19T09:40:00+08:00", "2026-07-19T10:00:00+08:00", null),
                command(IntentCommand.Type.CREATE_SCHEDULE, "接女兒下課",
                        "2026-07-19T12:00:00+08:00", "2026-07-19T12:20:00+08:00", null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天九點四十分送女兒上英文課，十二點接女兒下課", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.CREATE_TASK, IntentCommand.Type.CREATE_TASK);
        assertThat(safe.commands()).extracting(IntentCommand::dueAt)
                .containsExactly(
                        "2026-07-19T09:40:00+08:00",
                        "2026-07-19T12:00:00+08:00");
        assertThat(safe.commands()).extracting(IntentCommand::startAt)
                .containsOnlyNulls();
    }

    @Test
    void transportResponsibilityUsesTheActionRoleInsteadOfAKinshipWordList() {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "送小明上課",
                "2026-07-19T09:00:00+08:00",
                "2026-07-19T10:00:00+08:00",
                null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天九點送小明去上課", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.CREATE_TASK);
        assertThat(safe.commands().getFirst().dueAt())
                .isEqualTo("2026-07-19T09:00:00+08:00");
    }

    @Test
    void connectiveJieZheDoesNotBecomeAPickupResponsibility() {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "上日文課",
                "2026-07-19T10:00:00+08:00",
                "2026-07-19T12:00:00+08:00",
                null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天我接著上日文課，十點到十二點", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.CREATE_SCHEDULE);
    }

    @Test
    void personalClassWithExplicitRangeRemainsACalendarInterval() {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "上日文課",
                "2026-07-19T10:00:00+08:00",
                "2026-07-19T12:00:00+08:00",
                null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "明天我要上日文課，十點到十二點", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.CREATE_SCHEDULE);
        assertThat(safe.commands().getFirst().startAt())
                .isEqualTo("2026-07-19T10:00:00+08:00");
        assertThat(safe.commands().getFirst().endAt())
                .isEqualTo("2026-07-19T12:00:00+08:00");
    }

    @Test
    void caregivingReminderWithoutATimeFailsClosed() {
        IntentScript raw = script(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "送兒子去安親班",
                null,
                null,
                null));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "幫我記得送兒子去安親班", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().reason()).contains("幾點提醒");
    }

    @Test
    void incompleteTeacherNoticeDoesNotDiscardAnotherGroundedScheduleInTheSameMessage() {
        IntentScript raw = script(
                commandWithSource(IntentCommand.Type.CREATE_SCHEDULE, "父親節活動",
                        "老師通知明天十點報到，沒有說幾點結束"),
                commandWithSource(IntentCommand.Type.CREATE_SCHEDULE, "產品會議",
                        "明天下午兩點產品會議"));

        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "老師通知明天十點報到，沒有說幾點結束；明天下午兩點產品會議", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().title()).isEqualTo("產品會議");
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

    @Test
    void modelCannotInventAnUnrelatedCommandOutsideTheCurrentUserText() {
        IntentScript raw = script(commandWithSource(IntentCommand.Type.CREATE_SCHEDULE,
                "接孩子下課", "明天下午四點接孩子下課"));

        IntentScript safe = IntentScriptSafetyPolicy.apply("你現在能做到什麼？", raw);

        assertThat(safe.commands()).hasSize(1);
        assertThat(safe.commands().getFirst().type()).isEqualTo(IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().reason())
                .contains("無法對應到你這次的原話")
                .doesNotContain("孩子", "下課");
    }

    @Test
    void mixedScriptKeepsGroundedCommandAndFailsClosedForInventedCommand() {
        IntentScript raw = script(
                commandWithSource(IntentCommand.Type.CREATE_TASK, "買牛奶", "幫我記得買牛奶"),
                commandWithSource(IntentCommand.Type.CREATE_SCHEDULE,
                        "接孩子下課", "明天下午四點接孩子下課"));

        IntentScript safe = IntentScriptSafetyPolicy.apply("幫我記得買牛奶", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.CREATE_TASK, IntentCommand.Type.UNKNOWN);
        assertThat(safe.commands().getFirst().sourceText()).isEqualTo("幫我記得買牛奶");
        assertThat(safe.commands().getLast().reason()).doesNotContain("孩子", "下課");
    }

    @Test
    void quotedAssistantContentCannotBecomeANewOperationSource() {
        IntentScript raw = script(commandWithSource(IntentCommand.Type.CREATE_SCHEDULE,
                "明天開會", "明天下午三點開會"));

        IntentScript safe = IntentScriptSafetyPolicy.apply("這個回答哪裡錯了？", raw);

        assertThat(safe.commands()).extracting(IntentCommand::type)
                .containsExactly(IntentCommand.Type.UNKNOWN);
    }

    @Test
    void legacySingleCommandWithoutSourceRemainsCompatible() {
        IntentScript raw = script(command(IntentCommand.Type.CREATE_TASK,
                "買牛奶", null, null, null));

        IntentScript safe = IntentScriptSafetyPolicy.apply("幫我記得買牛奶", raw);

        assertThat(safe.commands()).containsExactlyElementsOf(raw.commands());
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

    private static IntentCommand commandWithSource(IntentCommand.Type type, String title,
                                                   String sourceText) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null, null, sourceText);
    }
}
