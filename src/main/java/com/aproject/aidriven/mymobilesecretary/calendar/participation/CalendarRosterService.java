package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CalendarRosterService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;

    public CalendarRosterService(
            CalendarRegistrationAccess access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public CalendarRosterView managerRoster(
            UUID planId, CalendarParticipationScope scope) {
        CalendarRegistrationAccess.Target target =
                access.lockTarget(planId, scope);
        access.requireManager(target, true);
        List<CalendarRosterEntry> entries = jdbc.query(
                """
                SELECT actor.display_name,
                       registration.registration_state
                FROM calendar_registration registration
                JOIN app_user actor
                  ON actor.id = registration.created_by_user_id
                WHERE registration.plan_id = ?
                  AND registration.activity_id IS NOT DISTINCT FROM ?
                  AND registration.workspace_id = ?
                  AND registration.source_created_by_user_id = ?
                ORDER BY registration.joined_at, registration.id
                """,
                (row, ignored) -> new CalendarRosterEntry(
                        row.getString("display_name"),
                        row.getString("registration_state")),
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        Bucket bucket = bucket(target);
        int remaining = bucket.capacity() == null
                ? Integer.MAX_VALUE
                : Math.max(
                        0,
                        bucket.capacity() - bucket.committed());
        return new CalendarRosterView(
                entries,
                bucket.committed(),
                bucket.waitlisted(),
                stateCount(entries, "APPROVAL_REQUIRED"),
                stateCount(entries, "WITHDRAWN_BY_USER"),
                stateCount(entries, "REMOVED_BY_ORGANIZER"),
                remaining,
                bucket.overCapacity());
    }

    private Bucket bucket(CalendarRegistrationAccess.Target target) {
        List<Bucket> rows = jdbc.query(
                """
                SELECT policy.capacity,
                       bucket.committed_count,
                       bucket.waitlisted_count,
                       bucket.over_capacity_count
                FROM calendar_registration_policy policy
                JOIN calendar_capacity_bucket bucket
                  ON bucket.policy_id = policy.id
                 AND bucket.plan_id = policy.plan_id
                 AND bucket.workspace_id = policy.workspace_id
                 AND bucket.source_created_by_user_id =
                        policy.source_created_by_user_id
                WHERE policy.plan_id = ?
                  AND policy.activity_id IS NOT DISTINCT FROM ?
                  AND policy.workspace_id = ?
                  AND policy.source_created_by_user_id = ?
                """,
                (row, ignored) -> {
                    Number capacity = (Number) row.getObject("capacity");
                    return new Bucket(
                            capacity == null
                                    ? null
                                    : capacity.intValue(),
                            row.getInt("committed_count"),
                            row.getInt("waitlisted_count"),
                            row.getInt("over_capacity_count"));
                },
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Registration policy capacity bucket is missing");
        }
        return rows.getFirst();
    }

    private static int stateCount(
            List<CalendarRosterEntry> entries, String state) {
        return (int) entries.stream()
                .filter(entry -> state.equals(entry.status()))
                .count();
    }

    private record Bucket(
            Integer capacity,
            int committed,
            int waitlisted,
            int overCapacity) {}
}
