package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarDatePolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));

    @Test
    void todayAnswerShowsGregorianRocAndWeekday() {
        IntentResult result = CalendarDatePolicy.answer("今天日期", CLOCK).orElseThrow();

        assertThat(result.message())
                .contains("國曆 2026/07/18（六）", "民國 115/07/18（六）");
    }

    @Test
    void rocDateIsNormalizedToStableGregorianDate() {
        assertThat(CalendarDatePolicy.normalizeForInterpretation(
                "民國115年7月20日下午三點開會"))
                .contains("2026/07/20（一）", "原文：民國115年7月20日");
    }

    @Test
    void ambiguousYearRequiresClarificationAndBlocksModelMutation() {
        String text = "26/7/20下午三點開會";
        IntentCommand create = new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE,
                "開會", null, "2026-07-20T15:00:00+08:00",
                "2026-07-20T16:00:00+08:00", null, null, null,
                null, null, null, null, null);

        assertThat(CalendarDatePolicy.clarification(text).orElseThrow())
                .contains("無法確定是民國或西元", "確認前不會建立或修改資料");
        IntentScript guarded = CalendarDatePolicy.guard(text, new IntentScript(List.of(create)));
        assertThat(guarded.commands()).singleElement()
                .extracting(IntentCommand::type).isEqualTo(IntentCommand.Type.UNKNOWN);
    }

    @Test
    void weekdayQuestionUnderstandsUnlabelledRocYear() {
        IntentResult result = CalendarDatePolicy.answer("115/7/18是星期幾？", CLOCK).orElseThrow();

        assertThat(result.message())
                .contains("國曆 2026/07/18（六）", "民國 115/07/18（六）");
    }

    @Test
    void impossibleDateIsRejectedBeforeInterpretation() {
        assertThat(CalendarDatePolicy.clarification("西元2026/2/30排會議").orElseThrow())
                .contains("日期", "不存在", "確認前不會建立或修改資料");
    }
}
