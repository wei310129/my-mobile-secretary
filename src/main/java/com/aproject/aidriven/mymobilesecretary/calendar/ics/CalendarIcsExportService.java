package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarIcsExportService {

    private static final String LOSS_REPORT =
            "ACK, retry, escalation, shared reminder templates, and adoption review are not preserved";

    private final JdbcTemplate jdbc;
    private final CalendarIcsExportArtifactService artifacts;

    public CalendarIcsExportService(
            JdbcTemplate jdbc, CalendarIcsExportArtifactService artifacts) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
    }

    public CalendarIcsExportArtifact export(CalendarIcsExportCommand command) {
        if (command == null
                || command.planId() == null
                || command.profile() == null) {
            throw new IllegalArgumentException("A calendar export command is required");
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        List<CalendarIcsEvent> events = jdbc.query(
                """
                SELECT plan.id, plan.title, plan.timed_start, plan.timed_end,
                       plan.zone_id,
                       COALESCE(string_agg(node.label, '; ' ORDER BY node.created_at), '')
                            AS node_summary
                FROM calendar_plan plan
                LEFT JOIN calendar_time_node node
                  ON node.plan_id = plan.id
                 AND node.workspace_id = plan.workspace_id
                 AND node.created_by_user_id = plan.created_by_user_id
                LEFT JOIN calendar_plan_ownership ownership
                  ON ownership.plan_id = plan.id
                 AND ownership.workspace_id = plan.workspace_id
                 AND ownership.source_created_by_user_id =
                        plan.created_by_user_id
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.status = 'ACTIVE'
                  AND plan.placement_kind = 'TIMED_INTERVAL'
                  AND COALESCE(ownership.owner_user_id,
                               plan.created_by_user_id) = ?
                GROUP BY plan.id, plan.title, plan.timed_start,
                         plan.timed_end, plan.zone_id
                """,
                (row, ignored) -> new CalendarIcsEvent(
                        row.getObject("id", UUID.class),
                        row.getString("title"),
                        row.getString("node_summary"),
                        instant(row.getTimestamp("timed_start")),
                        instant(row.getTimestamp("timed_end")),
                        ZoneId.of(row.getString("zone_id"))),
                command.planId(),
                context.workspaceId(),
                context.actorId());
        if (events.size() != 1) {
            throw new SecurityException("Calendar projection is unavailable for export");
        }
        byte[] content = CalendarIcsWriter.write(events);
        return artifacts.create(new CalendarIcsExportRequest(
                command.requestId(),
                command.planId(),
                command.windowStart(),
                command.windowEndExclusive(),
                command.profile(),
                LOSS_REPORT,
                content));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
