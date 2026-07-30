package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanIdentityView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
public class CalendarIcsImportService {

    private static final long TTL_SECONDS = 24 * 60 * 60;

    private final JdbcTemplate jdbc;
    private final MediaObjectStorage storage;
    private final CalendarApplicationService calendars;
    private final Clock clock;

    public CalendarIcsImportService(
            JdbcTemplate jdbc,
            MediaObjectStorage storage,
            CalendarApplicationService calendars,
            Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.calendars = calendars;
        this.clock = clock;
    }

    public CalendarIcsImportBatchView upload(
            CalendarIcsImportUploadRequest request) {
        requireUpload(request);
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        CalendarIcsImportDocument document =
                CalendarIcsBoundedParser.parse(request.content());
        if (document.candidates().isEmpty()) {
            throw new IllegalArgumentException("ICS import contains no VEVENT");
        }
        String source = request.importSource().strip();
        String fingerprint = hash(request.content());
        List<UUID> existing = jdbc.queryForList(
                """
                SELECT id
                FROM calendar_ics_import_batch
                WHERE import_source = ? AND source_fingerprint = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                UUID.class,
                source,
                fingerprint,
                context.workspaceId(),
                context.actorId());
        if (existing.size() == 1) {
            return preview(existing.getFirst());
        }

        UUID batchId = UUID.randomUUID();
        String storageKey =
                batchId.toString().substring(0, 2) + "/" + batchId;
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusSeconds(TTL_SECONDS);
        storage.put(storageKey, request.content());
        deleteStoredObjectOnRollback(storageKey);
        int inserted = jdbc.update(
                    """
                    INSERT INTO calendar_ics_import_batch (
                        id, import_source, source_fingerprint, storage_key,
                        content_hash, size_bytes, scheduling_method,
                        event_count, parse_state, row_revision, expires_at,
                        created_at, workspace_id, created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREVIEW_READY', 1,
                        ?, ?, ?, ?)
                    ON CONFLICT (
                        workspace_id, created_by_user_id,
                        import_source, source_fingerprint)
                    DO NOTHING
                    """,
                    batchId,
                    source,
                    fingerprint,
                    storageKey,
                    hash(request.content()),
                    request.content().length,
                    bounded(document.method(), 20),
                    document.candidates().size(),
                    Timestamp.from(expiresAt),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId());
        if (inserted == 0) {
            storage.delete(storageKey);
            UUID id = jdbc.queryForObject(
                    """
                    SELECT id
                    FROM calendar_ics_import_batch
                    WHERE import_source = ? AND source_fingerprint = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                    """,
                    UUID.class,
                    source,
                    fingerprint,
                    context.workspaceId(),
                    context.actorId());
            return preview(id);
        }
        int ordinal = 0;
        for (CalendarIcsImportCandidate candidate : document.candidates()) {
            insertCandidate(
                    batchId, ++ordinal, document.method(), candidate, context, now);
        }
        return preview(batchId);
    }

    @Transactional(readOnly = true)
    public CalendarIcsImportBatchView preview(UUID batchId) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        List<BatchRow> batches = jdbc.query(
                """
                SELECT id, row_revision, parse_state, scheduling_method,
                       event_count, expires_at
                FROM calendar_ics_import_batch
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new BatchRow(
                        row.getObject("id", UUID.class),
                        row.getLong("row_revision"),
                        row.getString("parse_state"),
                        row.getString("scheduling_method"),
                        row.getInt("event_count"),
                        row.getTimestamp("expires_at").toInstant()),
                batchId,
                context.workspaceId(),
                context.actorId());
        if (batches.size() != 1) {
            throw unavailable();
        }
        BatchRow batch = batches.getFirst();
        List<CalendarIcsImportItemView> items = jdbc.query(
                """
                SELECT id, item_ordinal, row_revision, title,
                       description_preview, location_preview,
                       placement_kind, timed_start, timed_end, zone_id,
                       floating_time, all_day_start,
                       all_day_end_exclusive, recurrence_summary,
                       recurrence_supported, reminder_offset_seconds,
                       warning_summary, proposal_state,
                       materialized_plan_id
                FROM calendar_ics_import_item
                WHERE batch_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                ORDER BY item_ordinal
                """,
                CalendarIcsImportService::itemView,
                batchId,
                context.workspaceId(),
                context.actorId());
        return new CalendarIcsImportBatchView(
                batch.id(),
                batch.revision(),
                batch.state(),
                batch.method(),
                batch.eventCount(),
                batch.expiresAt(),
                items);
    }

    public CalendarPlanIdentityView confirm(
            CalendarIcsImportConfirmCommand command) {
        requireConfirmation(command);
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        List<ItemRow> rows = jdbc.query(
                """
                SELECT item.id, item.title, item.placement_kind,
                       item.timed_start, item.timed_end, item.zone_id,
                       item.floating_time, item.all_day_start,
                       item.all_day_end_exclusive,
                       item.recurrence_summary, item.proposal_state,
                       item.row_revision, item.confirmation_hash,
                       item.materialized_plan_id, batch.expires_at,
                       batch.parse_state
                FROM calendar_ics_import_item item
                JOIN calendar_ics_import_batch batch
                  ON batch.id = item.batch_id
                 AND batch.workspace_id = item.workspace_id
                 AND batch.created_by_user_id =
                        item.created_by_user_id
                WHERE item.id = ? AND item.workspace_id = ?
                  AND item.created_by_user_id = ?
                FOR UPDATE OF item
                """,
                CalendarIcsImportService::itemRow,
                command.itemId(),
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw unavailable();
        }
        ItemRow row = rows.getFirst();
        String confirmationHash = hash(
                command.requestId()
                        + "|"
                        + command.itemId()
                        + "|"
                        + command.expectedRevision()
                        + "|"
                        + command.includeTitle()
                        + "|"
                        + command.includeTiming()
                        + "|"
                        + command.resolvedFloatingZone());
        if ("MATERIALIZED".equals(row.state())) {
            if (!MessageDigest.isEqual(
                    confirmationHash.getBytes(StandardCharsets.US_ASCII),
                    row.confirmationHash()
                            .getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException(
                        "ICS confirmation conflicts with prior materialization");
            }
            return calendars.getPlanById(row.materializedPlanId());
        }
        if (!"PENDING_CONFIRMATION".equals(row.state())
                || !"PREVIEW_READY".equals(row.batchState())
                || row.revision() != command.expectedRevision()
                || !Instant.now(clock).isBefore(row.expiresAt())
                || !command.includeTitle()
                || !command.includeTiming()
                || row.recurrenceSummary() != null) {
            throw unavailable();
        }
        CalendarPlacement placement = placement(
                row, command.resolvedFloatingZone());
        CalendarPlanIdentityView created = calendars.createPlanWithIdentity(
                new CreateCalendarPlanCommand(
                        "ics-import-" + confirmationHash,
                        row.title(),
                        placement,
                        "ICS_IMPORT",
                        null,
                        null,
                        List.of(),
                        List.of()));
        int updated = jdbc.update(
                """
                UPDATE calendar_ics_import_item
                SET proposal_state = 'MATERIALIZED',
                    confirmation_hash = ?, materialized_plan_id = ?,
                    materialized_at = ?, row_revision = row_revision + 1
                WHERE id = ? AND row_revision = ?
                  AND proposal_state = 'PENDING_CONFIRMATION'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                confirmationHash,
                created.planId(),
                Timestamp.from(Instant.now(clock)),
                row.id(),
                command.expectedRevision(),
                context.workspaceId(),
                context.actorId());
        if (updated != 1) {
            throw unavailable();
        }
        return created;
    }

    private void insertCandidate(
            UUID batchId,
            int ordinal,
            String method,
            CalendarIcsImportCandidate candidate,
            WorkspaceContext context,
            Instant now) {
        boolean timed = candidate.timed();
        boolean floating = candidate.floating();
        LocalDateTime timedStart = floating
                ? candidate.floatingStartsAt()
                : timed
                ? LocalDateTime.ofInstant(
                        candidate.startsAt(), candidate.zoneId())
                : null;
        LocalDateTime timedEnd = floating
                ? candidate.floatingEndsAt()
                : timed
                ? LocalDateTime.ofInstant(
                        candidate.endsAt(), candidate.zoneId())
                : null;
        List<Long> reminders = candidate.reminderSuggestions().stream()
                .map(Duration::getSeconds)
                .toList();
        boolean unsupported =
                candidate.recurring() && !candidate.recurrenceRuleSupported();
        jdbc.update(
                """
                INSERT INTO calendar_ics_import_item (
                    id, batch_id, item_ordinal, external_uid_hash,
                    recurrence_id_hash, external_sequence,
                    scheduling_method, title, description_preview,
                    location_preview, organizer_preview, attendee_preview,
                    placement_kind, timed_start, timed_end, zone_id,
                    floating_time, all_day_start, all_day_end_exclusive,
                    recurrence_summary, recurrence_supported,
                    reminder_offset_seconds, warning_summary,
                    proposal_state, row_revision, created_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """,
                UUID.randomUUID(),
                batchId,
                ordinal,
                nullableHash(candidate.uid()),
                nullableHash(candidate.recurrenceId()),
                candidate.sequence(),
                bounded(method, 20),
                bounded(defaultTitle(candidate.title()), 200),
                bounded(candidate.description(), 1000),
                bounded(candidate.location(), 300),
                timed ? "TIMED" : "ALL_DAY",
                timedStart,
                timedEnd,
                candidate.zoneId() == null
                        ? null
                        : candidate.zoneId().getId(),
                floating,
                candidate.allDayStart(),
                candidate.allDayEndExclusive(),
                bounded(candidate.recurrenceRule(), 500),
                candidate.recurrenceRuleSupported(),
                reminders.toArray(Long[]::new),
                bounded(
                        String.join("; ", candidate.warnings()),
                        2000),
                unsupported ? "UNSUPPORTED" : "PENDING_CONFIRMATION",
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
    }

    private static CalendarPlacement placement(
            ItemRow row, ZoneId resolvedFloatingZone) {
        if ("ALL_DAY".equals(row.placementKind())) {
            return CalendarPlacement.allDay(
                    row.allDayStart(), row.allDayEndExclusive());
        }
        ZoneId zone = row.floating()
                ? resolvedFloatingZone
                : ZoneId.of(row.zoneId());
        if (zone == null) {
            throw new IllegalArgumentException(
                    "Floating ICS time requires an explicit ZoneId");
        }
        return CalendarPlacement.interval(
                row.timedStart().atZone(zone).toInstant(),
                row.timedEnd().atZone(zone).toInstant(),
                zone);
    }

    private static CalendarIcsImportItemView itemView(
            ResultSet row, int ignored) throws SQLException {
        return new CalendarIcsImportItemView(
                row.getObject("id", UUID.class),
                row.getInt("item_ordinal"),
                row.getLong("row_revision"),
                row.getString("title"),
                row.getString("description_preview"),
                row.getString("location_preview"),
                row.getString("placement_kind"),
                localDateTime(row, "timed_start"),
                localDateTime(row, "timed_end"),
                row.getString("zone_id"),
                row.getBoolean("floating_time"),
                row.getObject("all_day_start", LocalDate.class),
                row.getObject(
                        "all_day_end_exclusive", LocalDate.class),
                row.getString("recurrence_summary"),
                row.getBoolean("recurrence_supported"),
                longs(row.getArray("reminder_offset_seconds")),
                row.getString("warning_summary"),
                row.getString("proposal_state"),
                row.getObject("materialized_plan_id", UUID.class));
    }

    private static ItemRow itemRow(ResultSet row, int ignored)
            throws SQLException {
        return new ItemRow(
                row.getObject("id", UUID.class),
                row.getString("title"),
                row.getString("placement_kind"),
                localDateTime(row, "timed_start"),
                localDateTime(row, "timed_end"),
                row.getString("zone_id"),
                row.getBoolean("floating_time"),
                row.getObject("all_day_start", LocalDate.class),
                row.getObject(
                        "all_day_end_exclusive", LocalDate.class),
                row.getString("recurrence_summary"),
                row.getString("proposal_state"),
                row.getLong("row_revision"),
                row.getString("confirmation_hash"),
                row.getObject("materialized_plan_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("parse_state"));
    }

    private static LocalDateTime localDateTime(
            ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static List<Long> longs(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        List<Long> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(((Number) value).longValue());
        }
        return List.copyOf(result);
    }

    private void deleteStoredObjectOnRollback(String storageKey) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            try {
                                storage.delete(storageKey);
                            } catch (RuntimeException ignored) {
                                // Storage reconciliation removes orphaned evidence.
                            }
                        }
                    }
                });
    }

    private static void requireUpload(
            CalendarIcsImportUploadRequest request) {
        if (request == null
                || request.requestId() == null
                || request.requestId().isBlank()
                || request.importSource() == null
                || request.importSource().isBlank()
                || request.importSource().strip().length() > 80
                || request.content() == null) {
            throw new IllegalArgumentException(
                    "A bounded ICS import upload is required");
        }
    }

    private static void requireConfirmation(
            CalendarIcsImportConfirmCommand command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.itemId() == null
                || command.expectedRevision() < 1) {
            throw new IllegalArgumentException(
                    "A revision-bound ICS confirmation is required");
        }
    }

    private static String defaultTitle(String value) {
        return value == null || value.isBlank()
                ? "Imported calendar event"
                : value.strip();
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maximum
                ? stripped
                : stripped.substring(0, maximum);
    }

    private static String nullableHash(String value) {
        return value == null || value.isBlank() ? null : hash(value);
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hash input is required");
        }
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", impossible);
        }
    }

    private static SecurityException unavailable() {
        return new SecurityException("ICS import proposal is unavailable");
    }

    private record BatchRow(
            UUID id,
            long revision,
            String state,
            String method,
            int eventCount,
            Instant expiresAt) {}

    private record ItemRow(
            UUID id,
            String title,
            String placementKind,
            LocalDateTime timedStart,
            LocalDateTime timedEnd,
            String zoneId,
            boolean floating,
            LocalDate allDayStart,
            LocalDate allDayEndExclusive,
            String recurrenceSummary,
            String state,
            long revision,
            String confirmationHash,
            UUID materializedPlanId,
            Instant expiresAt,
            String batchState) {}
}
