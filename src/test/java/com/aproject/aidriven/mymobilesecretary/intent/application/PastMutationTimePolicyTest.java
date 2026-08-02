package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PastMutationTimePolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T06:00:00Z"),
            ZoneId.of("Asia/Taipei"));

    @Test
    void pastScheduleProposesNextSameLocalTimeAndMutatesNothing() {
        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "今天下午一點開會",
                script(command(
                        IntentCommand.Type.CREATE_SCHEDULE,
                        null,
                        "2026-08-02T13:00:00+08:00")),
                CLOCK);

        assertThat(safe.commands()).singleElement().satisfies(command -> {
            assertThat(command.type()).isEqualTo(IntentCommand.Type.UNKNOWN);
            assertThat(command.reason())
                    .contains("時間已經過了", "2026/08/03 13:00", "要改到這個時間嗎？")
                    .doesNotContain("startAt", "validation");
        });
    }

    @Test
    void pastTaskDeadlineUsesTheSameGuard() {
        IntentScript safe = IntentScriptSafetyPolicy.apply(
                "今天中午提醒我繳費",
                script(command(
                        IntentCommand.Type.CREATE_TASK,
                        "2026-08-02T12:00:00+08:00",
                        null)),
                CLOCK);

        assertThat(safe.commands()).singleElement()
                .extracting(IntentCommand::type)
                .isEqualTo(IntentCommand.Type.UNKNOWN);
    }

    @Test
    void futureMutationPassesThrough() {
        IntentScript original = script(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                null,
                "2026-08-02T15:00:00+08:00"));

        assertThat(IntentScriptSafetyPolicy.apply(
                        "今天下午三點開會", original, CLOCK))
                .isEqualTo(original);
    }

    @Test
    void readOnlyHistoricalQueryIsNotBlocked() {
        IntentScript original = script(command(
                IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
                null,
                "2026-07-01T00:00:00+08:00"));

        assertThat(PastMutationTimePolicy.guard(original, CLOCK))
                .isEqualTo(original);
    }

    @Test
    void malformedModelTimeIsLeftForExistingValidation() {
        IntentScript original = script(command(
                IntentCommand.Type.CREATE_SCHEDULE, null, "not-a-time"));

        assertThat(PastMutationTimePolicy.guard(original, CLOCK))
                .isEqualTo(original);
    }

    private static IntentScript script(IntentCommand command) {
        return new IntentScript(List.of(command));
    }

    private static IntentCommand command(
            IntentCommand.Type type, String dueAt, String startAt) {
        return new IntentCommand(
                type,
                "測試",
                dueAt,
                startAt,
                startAt == null ? null : "2026-08-02T16:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
