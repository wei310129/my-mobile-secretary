package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarOrganizerAssignmentService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarOrganizerAssignmentService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarOrganizerAssignmentView change(
            CalendarOrganizerAssignmentChange command) {
        requireCommand(command);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        access.requireOwner(target);
        access.requireActiveMember(
                command.managerUserId(), target.context());
        if (command.managerUserId().equals(target.effectiveOwnerId())) {
            throw new BusinessException(
                    "CALENDAR_MANAGER_OWNER_IS_IMPLICIT",
                    "The effective owner already has participant-manager authority");
        }
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + command.managerUserId()
                        + "|"
                        + command.rosterPermission()
                        + "|"
                        + command.notificationRecipient()
                        + "|"
                        + command.action());
        access.lock("organizer-assignment-request|" + requestHash);
        access.lock("organizer-assignment-target|"
                + target.context().workspaceId()
                + "|"
                + target.planId()
                + "|"
                + target.activityId()
                + "|"
                + command.managerUserId());
        AssignmentRow replay =
                byRequest(requestHash, target);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(replay);
        }
        AssignmentRow current =
                currentForUpdate(command.managerUserId(), target);
        if (current == null && command.expectedRevision() != 0) {
            throw revisionConflict();
        }
        if (current != null
                && current.revision() != command.expectedRevision()) {
            throw revisionConflict();
        }
        CalendarOrganizerAssignmentStatus status =
                command.action() == CalendarOrganizerAssignmentAction.ASSIGN
                        ? CalendarOrganizerAssignmentStatus.ACTIVE
                        : CalendarOrganizerAssignmentStatus.REVOKED;
        if (current == null
                && status == CalendarOrganizerAssignmentStatus.REVOKED) {
            throw new BusinessException(
                    "CALENDAR_MANAGER_ASSIGNMENT_NOT_FOUND",
                    "No participant-manager assignment exists to revoke");
        }
        Instant now = Instant.now(clock);
        long revision = current == null ? 1 : current.revision() + 1;
        UUID id = current == null ? UUID.randomUUID() : current.id();
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_organizer_assignment (
                        id, plan_id, activity_id, target_scope,
                        manager_user_id, assignment_role,
                        roster_permission, notification_recipient,
                        assignment_status, assignment_revision,
                        operation_request_hash, operation_payload_hash,
                        created_at, updated_at, workspace_id,
                        source_created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, ?, 'PARTICIPANT_MANAGER',
                        ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    target.planId(),
                    target.activityId(),
                    target.scopeType().name(),
                    command.managerUserId(),
                    command.rosterPermission(),
                    command.notificationRecipient(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.sourceOwnerId());
        } else {
            int changed = jdbc.update(
                    """
                    UPDATE calendar_organizer_assignment
                    SET roster_permission = ?,
                        notification_recipient = ?,
                        assignment_status = ?,
                        assignment_revision = assignment_revision + 1,
                        operation_request_hash = ?,
                        operation_payload_hash = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND source_created_by_user_id = ?
                      AND assignment_revision = ?
                    """,
                    command.rosterPermission(),
                    command.notificationRecipient(),
                    status.name(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.sourceOwnerId(),
                    command.expectedRevision());
            if (changed != 1) {
                throw revisionConflict();
            }
        }
        recordReceipt(
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "MANAGER|" + id + "|" + revision),
                status,
                revision,
                now,
                target);
        return view(currentForUpdate(command.managerUserId(), target));
    }

    private void recordReceipt(
            String requestHash,
            String payloadHash,
            String semanticHash,
            CalendarOrganizerAssignmentStatus status,
            long revision,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, activity_id,
                    operation_request_hash, operation_payload_hash,
                    semantic_identity_hash, result_state,
                    result_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, 'MANAGER_ASSIGNMENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id,
                    request_kind, operation_request_hash)
                DO NOTHING
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                requestHash,
                payloadHash,
                semanticHash,
                status.name(),
                revision,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private AssignmentRow byRequest(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        List<AssignmentRow> rows = jdbc.query(
                selectAssignment()
                        + """
                         AND assignment.operation_request_hash = ?
                        """,
                CalendarOrganizerAssignmentService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                requestHash);
        return rows.stream().findFirst().orElse(null);
    }

    private AssignmentRow currentForUpdate(
            UUID managerUserId,
            CalendarRegistrationAccess.Target target) {
        List<AssignmentRow> rows = jdbc.query(
                selectAssignment()
                        + """
                         AND assignment.manager_user_id = ?
                         FOR UPDATE
                        """,
                CalendarOrganizerAssignmentService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                managerUserId);
        return rows.stream().findFirst().orElse(null);
    }

    private static String selectAssignment() {
        return """
                SELECT assignment.id, assignment.plan_id,
                       assignment.activity_id,
                       assignment.target_scope,
                       assignment.manager_user_id,
                       assignment.roster_permission,
                       assignment.notification_recipient,
                       assignment.assignment_status,
                       assignment.assignment_revision,
                       assignment.operation_payload_hash
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id = ?
                  AND assignment.activity_id IS NOT DISTINCT FROM ?
                  AND assignment.workspace_id = ?
                  AND assignment.source_created_by_user_id = ?
                """;
    }

    private static AssignmentRow row(
            java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        return new AssignmentRow(
                result.getObject("id", UUID.class),
                result.getObject("plan_id", UUID.class),
                result.getObject("activity_id", UUID.class),
                result.getString("target_scope"),
                result.getObject("manager_user_id", UUID.class),
                result.getBoolean("roster_permission"),
                result.getBoolean("notification_recipient"),
                result.getString("assignment_status"),
                result.getLong("assignment_revision"),
                result.getString("operation_payload_hash"));
    }

    private static CalendarOrganizerAssignmentView view(
            AssignmentRow row) {
        UUID targetId =
                row.activityId() == null ? row.planId() : row.activityId();
        return new CalendarOrganizerAssignmentView(
                row.planId(),
                new CalendarParticipationScope(
                        CalendarParticipationScopeType.valueOf(
                                row.scopeType()),
                        targetId),
                row.managerUserId(),
                row.rosterPermission(),
                row.notificationRecipient(),
                CalendarOrganizerAssignmentStatus.valueOf(row.status()),
                row.revision());
    }

    private static void requireCommand(
            CalendarOrganizerAssignmentChange command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.managerUserId() == null
                || command.action() == null
                || command.expectedRevision() < 0) {
            throw new IllegalArgumentException(
                    "A manager target, action, and revision are required");
        }
    }

    private static void requirePayload(
            String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The manager request key was used for another change");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_MANAGER_ASSIGNMENT_REVISION_CONFLICT",
                "Participant-manager assignment changed; reload before updating it");
    }

    private record AssignmentRow(
            UUID id,
            UUID planId,
            UUID activityId,
            String scopeType,
            UUID managerUserId,
            boolean rosterPermission,
            boolean notificationRecipient,
            String status,
            long revision,
            String payloadHash) {}
}
