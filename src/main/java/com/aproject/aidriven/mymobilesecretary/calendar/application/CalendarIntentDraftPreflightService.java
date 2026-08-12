package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteAssessment;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteAssessmentService;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjectionService;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryFilter;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryItem;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Assesses only adjacent routes touched by a not-yet-materialized Calendar proposal. */
@Service
public class CalendarIntentDraftPreflightService {

    private final PersonalRouteProjectionService projection;
    private final PersonalRouteAssessmentService assessments;
    private final CalendarQueryService calendarQuery;

    public CalendarIntentDraftPreflightService(
            PersonalRouteProjectionService projection,
            PersonalRouteAssessmentService assessments,
            CalendarQueryService calendarQuery) {
        this.projection = projection;
        this.assessments = assessments;
        this.calendarQuery = calendarQuery;
    }

    public PreflightResult assess(CalendarIntentDraftService.DraftView draft) {
        if (draft.placement() instanceof CalendarPlacement.TimedInterval interval) {
            List<CalendarQueryItem> overlaps = calendarQuery
                    .queryExcludingPlan(
                            CalendarQueryFilter.range(
                                    interval.start(), interval.end(), interval.zoneId(), 10, 0),
                            draft.materializedPlanId())
                    .items();
            if (!overlaps.isEmpty()) {
                return PreflightResult.timeConflict(draft, overlaps);
            }
        }
        UUID actor = WorkspaceContextHolder.requireContext().actorId();
        List<PersonalRouteConstraint> candidate = candidate(draft, actor);
        if (candidate.isEmpty()) return PreflightResult.clear();
        var all = new ArrayList<>(projection.current().routeConstraints());
        if (draft.materializedPlanId() != null) {
            all.removeIf(constraint ->
                    draft.materializedPlanId().equals(constraint.planId()));
        }
        all.addAll(candidate);
        List<PersonalRouteAssessment> relevant = assessments
                .assessAdjacentExcludingInternalPlan(all, draft.id())
                .stream()
                .filter(assessment -> crossesCandidateBoundary(assessment, draft.id()))
                .toList();
        boolean risk = relevant.stream().anyMatch(PersonalRouteAssessment::isRouteRisk);
        boolean insufficient = relevant.stream().anyMatch(assessment ->
                assessment.status() == PersonalRouteStatus.INSUFFICIENT_EVIDENCE);
        if (!risk && !insufficient) return PreflightResult.clear();
        String status = risk ? "ROUTE_RISK" : "INSUFFICIENT_EVIDENCE";
        return new PreflightResult(status, fingerprint(draft, relevant), relevant, List.of());
    }

    private static List<PersonalRouteConstraint> candidate(
            CalendarIntentDraftService.DraftView draft, UUID actor) {
        if (draft.placement() instanceof CalendarPlacement.AllDay) return List.of();
        var result = new ArrayList<PersonalRouteConstraint>();
        boolean standaloneRoute = draft.transportOrigin() != null
                && draft.transportMode() != null
                && draft.transportOfferStatus()
                        == CalendarIntentDraftService.TransportOfferStatus.NONE;
        if (draft.placement() instanceof CalendarPlacement.TimedInterval interval) {
            result.add(constraint(
                    draft,
                    actor,
                    "start",
                    draft.title() + "開始",
                    interval.start(),
                    standaloneRoute ? draft.transportOrigin() : draft.location()));
            result.add(constraint(
                    draft,
                    actor,
                    "end",
                    draft.title() + "結束",
                    interval.end(),
                    draft.location()));
        } else {
            CalendarPlacement.TimedPoint point =
                    (CalendarPlacement.TimedPoint) draft.placement();
            result.add(constraint(
                    draft, actor, "start", draft.title(), point.time(), draft.location()));
        }
        return List.copyOf(result);
    }

    private static PersonalRouteConstraint constraint(
            CalendarIntentDraftService.DraftView draft,
            UUID actor,
            String key,
            String label,
            Instant time,
            com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation
                    location) {
        UUID nodeId = UUID.nameUUIDFromBytes(
                ("calendar-draft-route|" + draft.id() + "|" + key)
                        .getBytes(StandardCharsets.UTF_8));
        return new PersonalRouteConstraint(
                draft.id(),
                nodeId,
                actor,
                label,
                time,
                location,
                Adjustability.LOCKED,
                draft.revision());
    }

    private static boolean crossesCandidateBoundary(
            PersonalRouteAssessment assessment, UUID draftId) {
        if (assessment.from() == null || assessment.to() == null) return false;
        boolean fromCandidate = assessment.from().planId().equals(draftId);
        boolean toCandidate = assessment.to().planId().equals(draftId);
        return fromCandidate != toCandidate;
    }

    private static String fingerprint(
            CalendarIntentDraftService.DraftView draft,
            List<PersonalRouteAssessment> assessments) {
        String material = assessments.stream()
                .sorted(Comparator.comparing(assessment -> assessment.from().effectiveTime()))
                .map(assessment -> String.join("|",
                        assessment.status().name(),
                        assessment.from().planId().toString(),
                        assessment.from().nodeId().toString(),
                        assessment.from().planId().equals(draft.id())
                                ? "candidate"
                                : Long.toString(assessment.from().nodeRevision()),
                        assessment.to().planId().toString(),
                        assessment.to().nodeId().toString(),
                        assessment.to().planId().equals(draft.id())
                                ? "candidate"
                                : Long.toString(assessment.to().nodeRevision()),
                        assessment.from().effectiveTime().toString(),
                        assessment.to().effectiveTime().toString(),
                        assessment.requiredTravel() == null
                                ? "none"
                                : Long.toString(assessment.requiredTravel().toSeconds()),
                        assessment.availableGap() == null
                                ? "none"
                                : Long.toString(assessment.availableGap().toSeconds())))
                .collect(java.util.stream.Collectors.joining(";"));
        return sha256("calendar-route-preflight-v1|" + draft.id() + "|" + material);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record PreflightResult(
            String status,
            String fingerprint,
            List<PersonalRouteAssessment> assessments,
            List<CalendarQueryItem> overlaps) {

        public PreflightResult {
            assessments = List.copyOf(assessments);
            overlaps = List.copyOf(overlaps);
        }

        public static PreflightResult clear() {
            return new PreflightResult(null, null, List.of(), List.of());
        }

        private static PreflightResult timeConflict(
                CalendarIntentDraftService.DraftView draft,
                List<CalendarQueryItem> overlaps) {
            String material = overlaps.stream()
                    .map(item -> item.title() + "|" + item.placement())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(";"));
            return new PreflightResult(
                    "ROUTE_RISK",
                    sha256("calendar-time-conflict-v1|" + draft.id() + "|" + material),
                    List.of(),
                    overlaps);
        }

        public boolean requiresConfirmation() {
            return status != null;
        }
    }
}
