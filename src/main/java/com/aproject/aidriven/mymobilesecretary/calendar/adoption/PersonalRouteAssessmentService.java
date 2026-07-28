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
        List<PersonalRouteConstraint> constraints = input.stream()
                .sorted(Comparator.comparing(PersonalRouteConstraint::effectiveTime)
                        .thenComparing(PersonalRouteConstraint::nodeKey))
                .toList();
        if (constraints.isEmpty()) {
            return new PersonalRouteAssessment(
                    PersonalRouteStatus.NO_CONSTRAINTS, null, null, null, null);
        }
        for (int index = 1; index < constraints.size(); index++) {
            PersonalRouteConstraint from = constraints.get(index - 1);
            PersonalRouteConstraint to = constraints.get(index);
            Duration gap = Duration.between(from.effectiveTime(), to.effectiveTime());
            if (from.location() == null || to.location() == null) {
                return new PersonalRouteAssessment(
                        PersonalRouteStatus.INSUFFICIENT_EVIDENCE,
                        from.nodeKey(),
                        to.nodeKey(),
                        null,
                        gap);
            }
            if (from.location().samePlace(to.location())) {
                continue;
            }
            Duration required = estimator.estimate(
                    from.location().latitude(),
                    from.location().longitude(),
                    to.location().latitude(),
                    to.location().longitude(),
                    from.effectiveTime());
            if (!gap.isNegative() && required.compareTo(gap) <= 0) {
                continue;
            }
            PersonalRouteStatus status =
                    from.adjustability() == Adjustability.WINDOWED
                                    || to.adjustability() == Adjustability.WINDOWED
                            ? PersonalRouteStatus.ALTERNATIVE_AVAILABLE
                            : PersonalRouteStatus.IMPOSSIBLE;
            return new PersonalRouteAssessment(
                    status, from.nodeKey(), to.nodeKey(), required, gap);
        }
        return new PersonalRouteAssessment(
                PersonalRouteStatus.FEASIBLE, null, null, Duration.ZERO, Duration.ZERO);
    }
}
