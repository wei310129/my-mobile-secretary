package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentScriptDateRangePolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void tomorrowLoadQueryReceivesOneExactTaipeiCalendarDay() {
        IntentScript safe = IntentScriptDateRangePolicy.apply("明天行程排太滿嗎",
                script(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY), NOW);

        IntentCommand command = safe.commands().getFirst();
        assertThat(command.startAt()).isEqualTo("2026-07-19T00:00+08:00");
        assertThat(command.endAt()).isEqualTo("2026-07-20T00:00+08:00");
    }

    @Test
    void thisWeekUsesCalendarWeekInsteadOfAnUnboundedDefault() {
        IntentScript safe = IntentScriptDateRangePolicy.apply("這週哪天最空",
                script(IntentCommand.Type.SUGGEST_FREE_SLOT), NOW);

        IntentCommand command = safe.commands().getFirst();
        assertThat(command.startAt()).isEqualTo("2026-07-13T00:00+08:00");
        assertThat(command.endAt()).isEqualTo("2026-07-20T00:00+08:00");
    }

    private static IntentScript script(IntentCommand.Type type) {
        return new IntentScript(List.of(new IntentCommand(type, null, null, null,
                null, null, null, null, null, null, null, null, null)));
    }
}
