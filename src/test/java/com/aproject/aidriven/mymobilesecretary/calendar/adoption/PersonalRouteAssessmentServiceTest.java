package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelTimeEstimator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonalRouteAssessmentServiceTest {

    private static final Instant TEN = Instant.parse("2026-07-25T02:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    @Mock private TravelTimeEstimator estimator;

    @Test
    void unadoptedProjectionHasNoConstraintsAndSamePlaceOverlapIsFeasible() {
        PersonalRouteAssessmentService service =
                new PersonalRouteAssessmentService(estimator);

        assertThat(service.assess(List.of()).status())
                .isEqualTo(PersonalRouteStatus.NO_CONSTRAINTS);
        assertThat(service.assess(List.of(
                                constraint("first", TEN, 25.0, 121.5, Adjustability.LOCKED),
                                constraint("second", TEN, 25.0, 121.5, Adjustability.LOCKED)))
                        .status())
                .isEqualTo(PersonalRouteStatus.FEASIBLE);
    }

    @Test
    void reachableImpossibleAndWindowedAlternativeAreDeterministic() {
        PersonalRouteConstraint first =
                constraint("first", TEN, 25.0, 121.5, Adjustability.LOCKED);
        PersonalRouteConstraint locked =
                constraint("locked", TEN.plus(Duration.ofHours(1)), 24.9, 121.1, Adjustability.LOCKED);
        PersonalRouteConstraint windowed =
                constraint("windowed", TEN.plus(Duration.ofHours(1)), 24.9, 121.1, Adjustability.WINDOWED);
        when(estimator.estimateEvidence(25.0, 121.5, 24.9, 121.1, TEN))
                .thenReturn(
                        TravelTimeEstimator.TravelTimeEvidence.routed(
                                Duration.ofMinutes(30),
                                TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED),
                        TravelTimeEstimator.TravelTimeEvidence.routed(
                                Duration.ofMinutes(90),
                                TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED),
                        TravelTimeEstimator.TravelTimeEvidence.routed(
                                Duration.ofMinutes(90),
                                TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));
        PersonalRouteAssessmentService service =
                new PersonalRouteAssessmentService(estimator);

        assertThat(service.assess(List.of(first, locked)).status())
                .isEqualTo(PersonalRouteStatus.FEASIBLE);
        assertThat(service.assess(List.of(first, locked)).status())
                .isEqualTo(PersonalRouteStatus.IMPOSSIBLE);
        assertThat(service.assess(List.of(first, windowed)).status())
                .isEqualTo(PersonalRouteStatus.ALTERNATIVE_AVAILABLE);
    }

    @Test
    void approximationIsInsufficientEvidenceEvenWhenItsDurationFits() {
        PersonalRouteConstraint first =
                constraint("first", TEN, 25.0, 121.5, Adjustability.LOCKED);
        PersonalRouteConstraint second =
                constraint("second", TEN.plus(Duration.ofHours(1)), 24.9, 121.1,
                        Adjustability.LOCKED);
        when(estimator.estimateEvidence(25.0, 121.5, 24.9, 121.1, TEN))
                .thenReturn(TravelTimeEstimator.TravelTimeEvidence.approximation(
                        Duration.ofMinutes(30)));

        assertThat(new PersonalRouteAssessmentService(estimator)
                        .assess(List.of(first, second)).status())
                .isEqualTo(PersonalRouteStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void trustedPlaceLabelWithoutCoordinatesDoesNotInventRouteEvidence() {
        PersonalRouteConstraint first = constraint(
                "first", TEN, 25.0, 121.5, Adjustability.LOCKED);
        PersonalRouteConstraint second = new PersonalRouteConstraint(
                UUID.nameUUIDFromBytes("plan-second".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("node-second".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                OWNER,
                "second",
                TEN.plus(Duration.ofHours(1)),
                new CalendarLocation("系統機場", null, null),
                Adjustability.LOCKED,
                1);

        assertThat(new PersonalRouteAssessmentService(estimator)
                        .assess(List.of(first, second)).status())
                .isEqualTo(PersonalRouteStatus.INSUFFICIENT_EVIDENCE);
        verifyNoInteractions(estimator);
    }

    @Test
    void unlinkedConstraintBeyondSixHoursIsIgnoredBeforeLocationAssessment() {
        PersonalRouteConstraint first =
                constraint("first", TEN, 25.0, 121.5, Adjustability.LOCKED);
        PersonalRouteConstraint distant = new PersonalRouteConstraint(
                UUID.nameUUIDFromBytes("plan-distant".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("node-distant".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                OWNER,
                "distant",
                TEN.plus(Duration.ofHours(14)),
                null,
                Adjustability.LOCKED,
                1);

        assertThat(new PersonalRouteAssessmentService(estimator)
                        .assess(List.of(first, distant)).status())
                .isEqualTo(PersonalRouteStatus.NO_CONSTRAINTS);
        verifyNoInteractions(estimator);
    }

    @Test
    void samePlanTypedLinkageCanCrossSixHours() {
        UUID planId = UUID.randomUUID();
        PersonalRouteConstraint first = constraint(
                planId, "first", TEN, 25.0, 121.5, Adjustability.LOCKED);
        PersonalRouteConstraint linked = constraint(
                planId,
                "linked",
                TEN.plus(Duration.ofHours(14)),
                24.9,
                121.1,
                Adjustability.LOCKED);
        when(estimator.estimateEvidence(25.0, 121.5, 24.9, 121.1, TEN))
                .thenReturn(TravelTimeEstimator.TravelTimeEvidence.routed(
                        Duration.ofHours(2),
                        TravelTimeEstimator.EvidenceSource.CUSTOM_ROUTED));

        assertThat(new PersonalRouteAssessmentService(estimator)
                        .assess(List.of(first, linked)).status())
                .isEqualTo(PersonalRouteStatus.FEASIBLE);
    }

    private static PersonalRouteConstraint constraint(
            String key,
            Instant time,
            double latitude,
            double longitude,
            Adjustability adjustability) {
        return constraint(
                UUID.nameUUIDFromBytes(("plan-" + key)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                key,
                time,
                latitude,
                longitude,
                adjustability);
    }

    private static PersonalRouteConstraint constraint(
            UUID planId,
            String key,
            Instant time,
            double latitude,
            double longitude,
            Adjustability adjustability) {
        return new PersonalRouteConstraint(
                planId,
                UUID.nameUUIDFromBytes(("node-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                OWNER,
                key,
                time,
                new CalendarLocation("地點 " + key, latitude, longitude),
                adjustability,
                1);
    }
}
