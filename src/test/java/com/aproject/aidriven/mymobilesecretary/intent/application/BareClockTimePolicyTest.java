package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BareClockTimePolicyTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void oneOClockPolicyUsesInjectedTaipeiClock(
            String name, String now, String text, String expected) {
        var clarification = BareClockTimePolicy.clarification(
                text, Clock.fixed(Instant.parse(now), TAIPEI));

        if (expected == null) {
            assertThat(clarification).isEmpty();
        } else {
            assertThat(clarification).hasValueSatisfying(
                    message -> assertThat(message).contains(expected));
        }
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(
                        "before 21 prefers afternoon",
                        "2026-08-02T12:59:00Z",
                        "明天1點開會",
                        "先理解為下午 1 點"),
                Arguments.of(
                        "at 21 asks AM or PM",
                        "2026-08-02T13:00:00Z",
                        "明天1點開會",
                        "01:00 還是 13:00"),
                Arguments.of(
                        "after 21 asks AM or PM",
                        "2026-08-02T15:00:00Z",
                        "明天一點出發",
                        "01:00 還是 13:00"),
                Arguments.of(
                        "explicit afternoon is stable",
                        "2026-08-02T12:00:00Z",
                        "明天下午1點開會",
                        null),
                Arguments.of(
                        "explicit midnight period is stable",
                        "2026-08-02T15:00:00Z",
                        "明天凌晨一點出發",
                        null),
                Arguments.of(
                        "eleven is not one",
                        "2026-08-02T12:00:00Z",
                        "明天11點開會",
                        null),
                Arguments.of(
                        "Chinese eleven is not one",
                        "2026-08-02T12:00:00Z",
                        "明天十一點開會",
                        null),
                Arguments.of(
                        "weekday roll call is not a clock",
                        "2026-08-02T12:00:00Z",
                        "週一點名",
                        null),
                Arguments.of(
                        "almost is not a clock",
                        "2026-08-02T12:00:00Z",
                        "差一點忘記",
                        null),
                Arguments.of(
                        "half past one remains ambiguous",
                        "2026-08-02T12:00:00Z",
                        "明天一點半出發",
                        "先理解為下午 1 點"));
    }
}
