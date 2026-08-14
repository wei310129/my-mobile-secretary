package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RouteJourneyKindPolicyTest {

    private static final Instant DEPARTURE = Instant.parse("2026-08-13T01:00:00Z");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void oneVerifiedDepartureTimeIsAlwaysAStandaloneTrip() {
        assertThat(RouteJourneyKindPolicy.resolve(
                        CalendarPlacement.point(DEPARTURE, TAIPEI),
                        RouteJourneyKindPolicy.SourceSemantics.EXPLICIT_STANDALONE_TRIP))
                .isEqualTo(RouteJourneyKind.STANDALONE_TRIP);
    }

    @Test
    void verifiedActivityIntervalIsActivityWithTransport() {
        assertThat(RouteJourneyKindPolicy.resolve(
                        CalendarPlacement.interval(DEPARTURE, DEPARTURE.plusSeconds(3600), TAIPEI),
                        RouteJourneyKindPolicy.SourceSemantics.EXPLICIT_ACTIVITY_WITH_TRANSPORT))
                .isEqualTo(RouteJourneyKind.ACTIVITY_WITH_TRANSPORT);
    }

    @Test
    void sourceSemanticsCannotOverrideVerifiedPlacement() {
        assertThatThrownBy(() -> RouteJourneyKindPolicy.resolve(
                        CalendarPlacement.point(DEPARTURE, TAIPEI),
                        RouteJourneyKindPolicy.SourceSemantics.EXPLICIT_ACTIVITY_WITH_TRANSPORT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
