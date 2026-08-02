package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarRouteRiskCoordinator {

    private final PersonalRouteProjectionService projection;
    private final PersonalRouteAssessmentService assessments;
    private final CalendarRouteRiskService risks;

    public CalendarRouteRiskCoordinator(
            PersonalRouteProjectionService projection,
            PersonalRouteAssessmentService assessments,
            CalendarRouteRiskService risks) {
        this.projection = projection;
        this.assessments = assessments;
        this.risks = risks;
    }

    @Transactional
    public SynchronizationResult synchronizeCurrent() {
        var notifications = new ArrayList<
                CalendarRouteRiskService.CalendarRouteRiskView>();
        List<PersonalRouteAssessment> currentAssessments = assessments.assessAdjacent(
                projection.current().routeConstraints());
        for (PersonalRouteAssessment assessment : currentAssessments) {
            if (assessment.isRouteRisk()) {
                var observed = risks.observe(assessment);
                if (observed.shouldNotify()) {
                    notifications.add(observed);
                }
            } else if (assessment.status() == PersonalRouteStatus.FEASIBLE) {
                risks.resolve(assessment);
            }
        }
        return new SynchronizationResult(
                currentAssessments, List.copyOf(notifications));
    }

    @Transactional
    public int confirmCurrentRisksForPlan(UUID planId) {
        if (planId == null) {
            throw new IllegalArgumentException("Calendar plan id is required");
        }
        int confirmed = 0;
        List<PersonalRouteAssessment> currentAssessments = assessments.assessAdjacent(
                projection.current().routeConstraints());
        for (PersonalRouteAssessment assessment : currentAssessments) {
            if (assessment.isRouteRisk()) {
                var observed = risks.observe(assessment);
                if (assessment.from().planId().equals(planId)
                        || assessment.to().planId().equals(planId)) {
                    risks.confirm(observed.riskId(), observed.revision());
                    confirmed++;
                }
            } else if (assessment.status() == PersonalRouteStatus.FEASIBLE) {
                risks.resolve(assessment);
            }
        }
        return confirmed;
    }

    public record SynchronizationResult(
            List<PersonalRouteAssessment> assessments,
            List<CalendarRouteRiskService.CalendarRouteRiskView> notifications) {

        public SynchronizationResult {
            assessments = List.copyOf(assessments);
            notifications = List.copyOf(notifications);
        }
    }
}
