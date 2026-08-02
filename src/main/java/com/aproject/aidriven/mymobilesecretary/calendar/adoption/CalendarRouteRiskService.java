package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarRouteRiskService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CalendarRouteRiskNotificationPolicy notificationPolicy;

    public CalendarRouteRiskService(
            JdbcTemplate jdbc,
            Clock clock,
            CalendarRouteRiskNotificationPolicy notificationPolicy) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.notificationPolicy = notificationPolicy;
    }

    public CalendarRouteRiskView observe(PersonalRouteAssessment assessment) {
        requireRisk(assessment);
        WorkspaceContext context = tenantContext();
        PersonalRouteConstraint from = assessment.from();
        PersonalRouteConstraint to = assessment.to();
        Instant now = clock.instant();
        Instant recommendedDeparture =
                to.effectiveTime().minus(assessment.requiredTravel());

        CurrentRisk current = findForUpdate(context, from, to);
        if (current == null) {
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update(
                    """
                    INSERT INTO calendar_route_risk (
                        id,
                        from_plan_id, from_node_id,
                        from_source_created_by_user_id,
                        from_node_revision,
                        to_plan_id, to_node_id,
                        to_source_created_by_user_id,
                        to_node_revision,
                        risk_kind, status, revision,
                        required_travel_seconds,
                        available_gap_seconds,
                        recommended_departure,
                        last_notified_required_seconds,
                        last_notified_departure, notified_at,
                        confirmed_at, resolved_at,
                        created_at, updated_at,
                        workspace_id, created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 'OPEN', 1, ?, ?, ?, ?, ?, ?,
                        NULL, NULL, ?, ?, ?, ?)
                    ON CONFLICT (
                        workspace_id, created_by_user_id,
                        from_plan_id, from_node_id,
                        from_source_created_by_user_id,
                        to_plan_id, to_node_id,
                        to_source_created_by_user_id)
                    DO NOTHING
                    """,
                    id,
                    from.planId(),
                    from.nodeId(),
                    from.sourceCreatedByUserId(),
                    from.nodeRevision(),
                    to.planId(),
                    to.nodeId(),
                    to.sourceCreatedByUserId(),
                    to.nodeRevision(),
                    assessment.status().name(),
                    assessment.requiredTravel().toSeconds(),
                    assessment.availableGap().toSeconds(),
                    Timestamp.from(recommendedDeparture),
                    assessment.requiredTravel().toSeconds(),
                    Timestamp.from(recommendedDeparture),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId());
            if (inserted == 1) {
                return view(
                        id, 1, "OPEN", assessment, recommendedDeparture, true);
            }
            current = findForUpdate(context, from, to);
        }
        if (current == null) {
            throw new IllegalStateException("Route risk conflict could not be reloaded");
        }

        boolean shouldNotify = notificationPolicy.shouldNotify(
                new CalendarRouteRiskNotificationPolicy.Snapshot(
                        CalendarRouteRiskNotificationPolicy.Lifecycle.valueOf(
                                current.status()),
                        current.fromNodeRevision(),
                        current.toNodeRevision(),
                        PersonalRouteStatus.valueOf(current.riskKind()),
                        Duration.ofSeconds(current.lastNotifiedRequiredSeconds()),
                        current.lastNotifiedDeparture()),
                from.nodeRevision(),
                to.nodeRevision(),
                assessment.status(),
                assessment.requiredTravel(),
                recommendedDeparture);
        long revision = current.revision() + (shouldNotify ? 1 : 0);

        if (shouldNotify) {
            jdbc.update(
                    """
                    UPDATE calendar_route_risk
                    SET from_node_revision = ?, to_node_revision = ?,
                        risk_kind = ?, status = 'OPEN', revision = ?,
                        required_travel_seconds = ?,
                        available_gap_seconds = ?,
                        recommended_departure = ?,
                        last_notified_required_seconds = ?,
                        last_notified_departure = ?, notified_at = ?,
                        confirmed_at = NULL, resolved_at = NULL,
                        updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    """,
                    from.nodeRevision(),
                    to.nodeRevision(),
                    assessment.status().name(),
                    revision,
                    assessment.requiredTravel().toSeconds(),
                    assessment.availableGap().toSeconds(),
                    Timestamp.from(recommendedDeparture),
                    assessment.requiredTravel().toSeconds(),
                    Timestamp.from(recommendedDeparture),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    current.id(),
                    context.workspaceId(),
                    context.actorId());
        } else {
            jdbc.update(
                    """
                    UPDATE calendar_route_risk
                    SET required_travel_seconds = ?,
                        available_gap_seconds = ?,
                        recommended_departure = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    """,
                    assessment.requiredTravel().toSeconds(),
                    assessment.availableGap().toSeconds(),
                    Timestamp.from(recommendedDeparture),
                    Timestamp.from(now),
                    current.id(),
                    context.workspaceId(),
                    context.actorId());
        }
        return view(
                current.id(),
                revision,
                shouldNotify ? "OPEN" : current.status(),
                assessment,
                recommendedDeparture,
                shouldNotify);
    }

    public CalendarRouteRiskView confirm(UUID riskId, long expectedRevision) {
        if (riskId == null || expectedRevision < 1) {
            throw new IllegalArgumentException("Risk id and positive revision are required");
        }
        WorkspaceContext context = tenantContext();
        CurrentRisk current = findByIdForUpdate(context, riskId);
        if (current == null) {
            throw new NotFoundException("Calendar route risk", "requested risk");
        }
        if (current.revision() != expectedRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_ROUTE_RISK",
                    "The route risk changed before confirmation");
        }
        if (current.status().equals("RESOLVED")) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_RISK_RESOLVED",
                    "The route risk is no longer active");
        }
        if (!current.status().equals("CONFIRMED")) {
            Instant now = clock.instant();
            jdbc.update(
                    """
                    UPDATE calendar_route_risk
                    SET status = 'CONFIRMED', confirmed_at = ?,
                        resolved_at = NULL, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ? AND revision = ?
                    """,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    riskId,
                    context.workspaceId(),
                    context.actorId(),
                    expectedRevision);
        }
        return new CalendarRouteRiskView(
                current.id(),
                current.revision(),
                current.riskKind(),
                "CONFIRMED",
                null,
                null,
                Duration.ofSeconds(current.requiredTravelSeconds()),
                Duration.ofSeconds(current.availableGapSeconds()),
                current.recommendedDeparture(),
                false);
    }

    public void resolve(PersonalRouteAssessment assessment) {
        if (assessment == null
                || assessment.status() != PersonalRouteStatus.FEASIBLE
                || assessment.from() == null
                || assessment.to() == null) {
            throw new IllegalArgumentException("A feasible adjacent assessment is required");
        }
        WorkspaceContext context = tenantContext();
        CurrentRisk current =
                findForUpdate(context, assessment.from(), assessment.to());
        if (current == null || current.status().equals("RESOLVED")) {
            return;
        }
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE calendar_route_risk
                SET status = 'RESOLVED', revision = revision + 1,
                    resolved_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                current.id(),
                context.workspaceId(),
                context.actorId());
    }

    private CurrentRisk findForUpdate(
            WorkspaceContext context,
            PersonalRouteConstraint from,
            PersonalRouteConstraint to) {
        List<CurrentRisk> rows = jdbc.query(
                """
                SELECT id, risk_kind, status, revision,
                    from_node_revision, to_node_revision,
                    required_travel_seconds,
                    available_gap_seconds, recommended_departure,
                    last_notified_required_seconds,
                    last_notified_departure
                FROM calendar_route_risk
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND from_plan_id = ? AND from_node_id = ?
                  AND from_source_created_by_user_id = ?
                  AND to_plan_id = ? AND to_node_id = ?
                  AND to_source_created_by_user_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> currentRisk(row),
                context.workspaceId(),
                context.actorId(),
                from.planId(),
                from.nodeId(),
                from.sourceCreatedByUserId(),
                to.planId(),
                to.nodeId(),
                to.sourceCreatedByUserId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CurrentRisk findByIdForUpdate(
            WorkspaceContext context, UUID riskId) {
        List<CurrentRisk> rows = jdbc.query(
                """
                SELECT id, risk_kind, status, revision,
                    from_node_revision, to_node_revision,
                    required_travel_seconds,
                    available_gap_seconds, recommended_departure,
                    last_notified_required_seconds,
                    last_notified_departure
                FROM calendar_route_risk
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> currentRisk(row),
                riskId,
                context.workspaceId(),
                context.actorId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static CurrentRisk currentRisk(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new CurrentRisk(
                row.getObject("id", UUID.class),
                row.getString("risk_kind"),
                row.getString("status"),
                row.getLong("revision"),
                row.getLong("from_node_revision"),
                row.getLong("to_node_revision"),
                row.getLong("required_travel_seconds"),
                row.getLong("available_gap_seconds"),
                row.getTimestamp("recommended_departure").toInstant(),
                row.getLong("last_notified_required_seconds"),
                row.getTimestamp("last_notified_departure").toInstant());
    }

    private static CalendarRouteRiskView view(
            UUID id,
            long revision,
            String status,
            PersonalRouteAssessment assessment,
            Instant recommendedDeparture,
            boolean shouldNotify) {
        return new CalendarRouteRiskView(
                id,
                revision,
                assessment.status().name(),
                status,
                assessment.fromNodeKey(),
                assessment.toNodeKey(),
                assessment.requiredTravel(),
                assessment.availableGap(),
                recommendedDeparture,
                shouldNotify);
    }

    private static void requireRisk(PersonalRouteAssessment assessment) {
        if (assessment == null
                || !assessment.isRouteRisk()
                || assessment.from() == null
                || assessment.to() == null
                || assessment.requiredTravel() == null
                || assessment.requiredTravel().isZero()
                || assessment.requiredTravel().isNegative()
                || assessment.availableGap() == null) {
            throw new IllegalArgumentException("A routed adjacent risk is required");
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar route risk requires tenant scope");
        }
        return context;
    }

    public record CalendarRouteRiskView(
            UUID riskId,
            long revision,
            String kind,
            String status,
            String fromNodeKey,
            String toNodeKey,
            Duration requiredTravel,
            Duration availableGap,
            Instant recommendedDeparture,
            boolean shouldNotify) {}

    private record CurrentRisk(
            UUID id,
            String riskKind,
            String status,
            long revision,
            long fromNodeRevision,
            long toNodeRevision,
            long requiredTravelSeconds,
            long availableGapSeconds,
            Instant recommendedDeparture,
            long lastNotifiedRequiredSeconds,
            Instant lastNotifiedDeparture) {}
}
