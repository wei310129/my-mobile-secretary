package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CalendarRouteRiskNotificationPolicyTest {

    private static final Instant DEPARTURE =
            Instant.parse("2026-08-02T01:00:00Z");

    private final CalendarRouteRiskNotificationPolicy policy =
            new CalendarRouteRiskNotificationPolicy();

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void notificationDecisionIsRevisionAndEvidenceBound(
            String ignored,
            CalendarRouteRiskNotificationPolicy.Snapshot previous,
            long fromRevision,
            long toRevision,
            PersonalRouteStatus kind,
            Duration required,
            Instant departure,
            boolean expected) {
        assertThat(policy.shouldNotify(
                        previous,
                        fromRevision,
                        toRevision,
                        kind,
                        required,
                        departure))
                .isEqualTo(expected);
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(
                scenario("same open risk is quiet", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90), DEPARTURE, false),
                scenario("same confirmed risk is quiet", snapshot(
                                CalendarRouteRiskNotificationPolicy.Lifecycle.CONFIRMED,
                                PersonalRouteStatus.IMPOSSIBLE),
                        1, 1, PersonalRouteStatus.IMPOSSIBLE,
                        minutes(90), DEPARTURE, false),
                scenario("faster ETA is quiet", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(70), DEPARTURE, false),
                scenario("later departure is quiet", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90),
                        DEPARTURE.plus(Duration.ofMinutes(15)), false),
                scenario("nine minute advance is quiet", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90),
                        DEPARTURE.minus(Duration.ofMinutes(9)), false),
                scenario("exact ten minute advance notifies", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90),
                        DEPARTURE.minus(Duration.ofMinutes(10)), true),
                scenario("one second ETA deterioration notifies", open(), 1, 1,
                        PersonalRouteStatus.IMPOSSIBLE,
                        Duration.ofMinutes(90).plusSeconds(1), DEPARTURE, true),
                scenario("from-node revision notifies", open(), 2, 1,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90), DEPARTURE, true),
                scenario("to-node revision notifies", open(), 1, 2,
                        PersonalRouteStatus.IMPOSSIBLE, minutes(90), DEPARTURE, true),
                scenario("alternative kind change notifies", open(), 1, 1,
                        PersonalRouteStatus.ALTERNATIVE_AVAILABLE,
                        minutes(90), DEPARTURE, true),
                scenario("resolved risk recurring notifies", snapshot(
                                CalendarRouteRiskNotificationPolicy.Lifecycle.RESOLVED,
                                PersonalRouteStatus.IMPOSSIBLE),
                        1, 1, PersonalRouteStatus.IMPOSSIBLE,
                        minutes(90), DEPARTURE, true),
                scenario("confirmed risk with worse ETA notifies", snapshot(
                                CalendarRouteRiskNotificationPolicy.Lifecycle.CONFIRMED,
                                PersonalRouteStatus.IMPOSSIBLE),
                        1, 1, PersonalRouteStatus.IMPOSSIBLE,
                        minutes(91), DEPARTURE, true));
    }

    private static Arguments scenario(
            String name,
            CalendarRouteRiskNotificationPolicy.Snapshot previous,
            long fromRevision,
            long toRevision,
            PersonalRouteStatus kind,
            Duration required,
            Instant departure,
            boolean expected) {
        return Arguments.of(
                name,
                previous,
                fromRevision,
                toRevision,
                kind,
                required,
                departure,
                expected);
    }

    private static CalendarRouteRiskNotificationPolicy.Snapshot open() {
        return snapshot(
                CalendarRouteRiskNotificationPolicy.Lifecycle.OPEN,
                PersonalRouteStatus.IMPOSSIBLE);
    }

    private static CalendarRouteRiskNotificationPolicy.Snapshot snapshot(
            CalendarRouteRiskNotificationPolicy.Lifecycle lifecycle,
            PersonalRouteStatus kind) {
        return new CalendarRouteRiskNotificationPolicy.Snapshot(
                lifecycle, 1, 1, kind, minutes(90), DEPARTURE);
    }

    private static Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}
