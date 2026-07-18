package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 使用者 2026-07-16 裁決:模糊時間語可給建議但必須回問,確認前不得建立/改期。 */
class VagueTimeGuardTest {

    @Test
    void afterWorkTaskAsksForConcreteTimeAndDowngradesGuessToSuggestion() {
        IntentCommand command = createTask("買菜", "2026-07-16T20:00:00+08:00");

        Optional<IntentResult> result = VagueTimeGuard.clarify("下班後提醒我買菜", command);

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.get().message())
                .contains("「下班後」")
                .contains("我建議 07/16 20:00")
                .contains("你確認之前我不會建立這件待辦");
    }

    @Test
    void weekendStaysAmbiguousEvenWithConcreteHour() {
        // 「週末早上十點」講了鐘點但沒講週六還是週日,仍要回問
        IntentCommand command = createSchedule("大掃除", "2026-07-18T10:00:00+08:00");

        Optional<IntentResult> result = VagueTimeGuard.clarify("週末早上十點幫我排大掃除", command);

        assertThat(result).isPresent();
        assertThat(result.get().message()).contains("「週末」", "排這個行程");
    }

    @Test
    void eveningWithoutHourAsksInsteadOfDefaulting() {
        Optional<IntentResult> result = VagueTimeGuard.clarify(
                "晚上提醒我吃藥", createTask("吃藥", null));

        assertThat(result).isPresent();
        assertThat(result.get().message()).contains("「晚上」");
        // LLM 沒猜時間就沒有建議行,但仍要回問
        assertThat(result.get().message()).doesNotContain("我建議");
    }

    @Test
    void concreteTimesPassThroughUntouched() {
        assertThat(VagueTimeGuard.clarify("明天晚上八點提醒我吃藥",
                createTask("吃藥", "2026-07-17T20:00:00+08:00"))).isEmpty();
        assertThat(VagueTimeGuard.clarify("週六早上十點幫我排大掃除",
                createSchedule("大掃除", "2026-07-18T10:00:00+08:00"))).isEmpty();
        assertThat(VagueTimeGuard.clarify("拿包裹改成今天11點",
                reschedule("拿包裹", "2026-07-16T11:00:00+08:00"))).isEmpty();
    }

    @Test
    void vagueRescheduleAsksBeforeMovingAnything() {
        Optional<IntentResult> result = VagueTimeGuard.clarify(
                "把週會改到下午", reschedule("週會", "2026-07-16T14:00:00+08:00"));

        assertThat(result).isPresent();
        assertThat(result.get().message()).contains("「下午」", "改時間");
    }

    @Test
    void personalTimeWindowQueryAsksForBoundsInsteadOfSearchingSevenDays() {
        IntentCommand command = new IntentCommand(IntentCommand.Type.SUGGEST_FREE_SLOT,
                null, null, "2026-07-18T08:00:00+08:00",
                "2026-07-18T12:00:00+08:00", null, null, null,
                null, null, null, null, null);

        Optional<IntentResult> lunch = VagueTimeGuard.clarify("午餐前有空檔嗎", command);
        Optional<IntentResult> sleep = VagueTimeGuard.clarify("睡前有一小時空檔嗎", command);
        Optional<IntentResult> morning = VagueTimeGuard.clarify("上午有空嗎", command);

        assertThat(lunch).isPresent();
        assertThat(lunch.get().message()).contains("「午餐前」", "確切要定在哪天幾點",
                "不會用猜測的時間窗查詢");
        assertThat(sleep).isPresent();
        assertThat(sleep.get().message()).contains("「睡前」");
        assertThat(morning).isPresent();
        assertThat(morning.get().message()).contains("「上午」");
    }

    @Test
    void recurringWithoutAnchorAsksForConcreteSchedule() {
        // 使用者裁決 #21/#22:每天要問幾點,每週要問週幾
        Optional<IntentResult> daily = VagueTimeGuard.clarify(
                "每天提醒我喝水", createTask("喝水", null));
        assertThat(daily).isPresent();
        assertThat(daily.get().message()).contains("「每天」", "每天早上八點");

        Optional<IntentResult> weekly = VagueTimeGuard.clarify(
                "每週提醒我交週報", createTask("交週報", "2030-08-09T09:00:00+08:00"));
        assertThat(weekly).isPresent();
        assertThat(weekly.get().message()).contains("「每週」");
    }

    @Test
    void recurringWithAnchorPassesThrough() {
        assertThat(VagueTimeGuard.clarify("每天早上八點提醒我喝水",
                createTask("喝水", "2026-07-17T08:00:00+08:00"))).isEmpty();
        assertThat(VagueTimeGuard.clarify("每週五提醒我交週報",
                createTask("交週報", "2026-07-17T09:00:00+08:00"))).isEmpty();
        assertThat(VagueTimeGuard.clarify("每月五號提醒我處理生活費",
                createTask("處理生活費", "2026-08-05T09:00:00+08:00"))).isEmpty();
    }

    @Test
    void timeWordsInsideTitleAreNotMistakenForVagueness() {
        // 行程名稱本身含「上午」:那是名字,不是時間語
        assertThat(VagueTimeGuard.clarify("幫我排歧義行程測試會議上午",
                createSchedule("歧義行程測試會議上午", "2027-09-05T10:00:00+08:00"))).isEmpty();
    }

    @Test
    void queryCommandsAreNeverGuarded() {
        IntentCommand list = new IntentCommand(IntentCommand.Type.LIST_TASKS, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(VagueTimeGuard.clarify("週末有什麼待辦", list)).isEmpty();
    }

    private static IntentCommand createTask(String title, String dueAt) {
        return new IntentCommand(IntentCommand.Type.CREATE_TASK, title, dueAt, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static IntentCommand createSchedule(String title, String startAt) {
        return new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE, title, null, startAt, null,
                null, null, null, null, null, null, null, null);
    }

    private static IntentCommand reschedule(String title, String dueAt) {
        return new IntentCommand(IntentCommand.Type.RESCHEDULE_TASK, title, dueAt, null, null,
                null, null, null, null, null, null, null, null);
    }
}
