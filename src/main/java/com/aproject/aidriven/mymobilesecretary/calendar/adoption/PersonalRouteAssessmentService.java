package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelTimeEstimator;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PersonalRouteAssessmentService {

    private final TravelTimeEstimator estimator;

    public PersonalRouteAssessmentService(TravelTimeEstimator estimator) {
        this.estimator = estimator;
    }

    public PersonalRouteAssessment assess(List<PersonalRouteConstraint> input) {
        List<PersonalRouteAssessment> assessments = assessAdjacent(input);
        if (assessments.isEmpty()) {
            return new PersonalRouteAssessment(
                    PersonalRouteStatus.NO_CONSTRAINTS, null, null, null, null);
        }
        return assessments.stream()
                .filter(assessment -> assessment.status() != PersonalRouteStatus.FEASIBLE)
                .findFirst()
                .orElse(new PersonalRouteAssessment(
                        PersonalRouteStatus.FEASIBLE,
                        null,
                        null,
                        Duration.ZERO,
                        Duration.ZERO));
    }

    public List<PersonalRouteAssessment> assessAdjacent(
            List<PersonalRouteConstraint> input) {
        List<PersonalRouteConstraint> constraints = List.copyOf(input).stream()
                .sorted(Comparator.comparing(PersonalRouteConstraint::effectiveTime)
                        .thenComparing(PersonalRouteConstraint::planId)
                        .thenComparing(PersonalRouteConstraint::nodeId))
                .toList();
        var assessments = new java.util.ArrayList<PersonalRouteAssessment>();
        for (int index = 1; index < constraints.size(); index++) {
            PersonalRouteConstraint from = constraints.get(index - 1);
            PersonalRouteConstraint to = constraints.get(index);
            Duration gap = Duration.between(from.effectiveTime(), to.effectiveTime());
            if (from.location() == null || to.location() == null) {
                assessments.add(new PersonalRouteAssessment(
                        PersonalRouteStatus.INSUFFICIENT_EVIDENCE,
                        from,
                        to,
                        null,
                        gap));
                continue;
            }
            if (from.location().samePlace(to.location())) {
                assessments.add(new PersonalRouteAssessment(
                        PersonalRouteStatus.FEASIBLE,
                        from,
                        to,
                        Duration.ZERO,
                        gap));
                continue;
            }
            var evidence = estimator.estimateEvidence(
                    from.location().latitude(),
                    from.location().longitude(),
                    to.location().latitude(),
                    to.location().longitude(),
                    from.effectiveTime());
            if (!evidence.supportsFeasibilityClaim()) {
                assessments.add(new PersonalRouteAssessment(
                        PersonalRouteStatus.INSUFFICIENT_EVIDENCE,
                        from,
                        to,
                        null,
                        gap));
                continue;
            }
            Duration required = evidence.duration();
            if (!gap.isNegative() && required.compareTo(gap) <= 0) {
                assessments.add(new PersonalRouteAssessment(
                        PersonalRouteStatus.FEASIBLE,
                        from,
                        to,
                        required,
                        gap));
                continue;
            }
            PersonalRouteStatus status =
                    from.adjustability() == Adjustability.WINDOWED
                                    || to.adjustability() == Adjustability.WINDOWED
                            ? PersonalRouteStatus.ALTERNATIVE_AVAILABLE
                            : PersonalRouteStatus.IMPOSSIBLE;
            assessments.add(new PersonalRouteAssessment(
                    status, from, to, required, gap));
        }
        return List.copyOf(assessments);
    }
}
