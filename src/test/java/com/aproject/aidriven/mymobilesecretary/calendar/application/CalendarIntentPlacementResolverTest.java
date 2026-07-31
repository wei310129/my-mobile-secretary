package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CalendarIntentPlacementResolverTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void oneClockTimeIsAPointEvenWhenTheModelSuppliesAnEnd() {
        assertThat(CalendarIntentPlacementResolver.resolve(command(
                        "2026-08-01T10:00:00+08:00",
                        "2026-08-01T11:00:00+08:00",
                        "明天上午十點領包裹")))
                .isEqualTo(CalendarPlacement.point(
                        Instant.parse("2026-08-01T02:00:00Z"), TAIPEI));
    }

    @Test
    void explicitDurationOverridesTheModelSuppliedEnd() {
        assertThat(CalendarIntentPlacementResolver.resolve(command(
                        "2026-08-01T10:00:00+08:00",
                        "2026-08-01T11:00:00+08:00",
                        "明天上午十點開會四十五分鐘")))
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-08-01T02:00:00Z"),
                        Instant.parse("2026-08-01T02:45:00Z"),
                        TAIPEI));
    }

    @Test
    void explicitRangeUsesTheTypedNormalizedEnd() {
        assertThat(CalendarIntentPlacementResolver.resolve(command(
                        "2026-08-01T10:00:00+08:00",
                        "2026-08-01T11:30:00+08:00",
                        "明天上午十點到十一點半開會")))
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-08-01T02:00:00Z"),
                        Instant.parse("2026-08-01T03:30:00Z"),
                        TAIPEI));
    }

    @Test
    void unrelatedNumberDoesNotBecomeADuration() {
        assertThat(CalendarIntentPlacementResolver.resolve(command(
                        "2026-08-01T10:00:00+08:00",
                        "2026-08-01T12:00:00+08:00",
                        "明天上午十點參加兩小時制學程說明會")))
                .isEqualTo(CalendarPlacement.point(
                        Instant.parse("2026-08-01T02:00:00Z"), TAIPEI));
    }

    @Test
    void legacyTypedIntervalWithoutSourceRemainsCompatible() {
        assertThat(CalendarIntentPlacementResolver.resolve(command(
                        "2026-08-01T10:00:00+08:00",
                        "2026-08-01T11:00:00+08:00",
                        null)))
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-08-01T02:00:00Z"),
                        Instant.parse("2026-08-01T03:00:00Z"),
                        TAIPEI));
    }

    private static IntentCommand command(String start, String end, String source) {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "測試行程",
                null,
                start,
                end,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                source);
    }
}
