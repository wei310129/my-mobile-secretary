package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarWaitlistReorderService {

    private static final String RECEIPT_KIND = "WAITLIST_REORDER";

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarWaitlistReorderService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarWaitlistReorderView reorder(
            CalendarWaitlistReorderCommand command) {
        requireCommand(command);
        String reason = command.reason().strip();
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = payloadHash(command, reason);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        access.requireManager(target, true);
        access.lock("waitlist-reorder-request|" + requestHash);

        Receipt replay = receipt(requestHash, target);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return replayView(requestHash, replay.resultRevision(), target);
        }

        List<EntryRow> current = lockActiveEntries(target);
        if (current.stream().anyMatch(row -> "OFFERED".equals(row.state()))) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_OFFERED_ENTRY_IMMUTABLE",
                    "Resolve the active waitlist offer before reordering this queue");
        }
        requireCompleteWaitingSet(command.orderedEntries(), current);

        SequenceBounds sequenceBounds = sequenceBounds(target);
        long temporaryOffset =
                sequenceBounds.maximum() + current.size() + 1;
        Instant now = Instant.now(clock);
        int shifted = jdbc.update(
                """
                UPDATE calendar_waitlist_entry
                SET queue_sequence = queue_sequence + ?
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND entry_state = 'WAITING'
                """,
                temporaryOffset,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (shifted != current.size()) {
            throw queueChanged();
        }

        List<CalendarWaitlistReorderView.Entry> reordered =
                new java.util.ArrayList<>(current.size());
        long resultRevision = 1;
        for (int index = 0; index < command.orderedEntries().size(); index++) {
            CalendarWaitlistReorderCommand.Entry requested =
                    command.orderedEntries().get(index);
            EntryRow previous = current.stream()
                    .filter(row -> row.id().equals(requested.entryId()))
                    .findFirst()
                    .orElseThrow();
            long sequence =
                    sequenceBounds.nonWaitingMaximum() + index + 1L;
            int changed = jdbc.update(
                    """
                    UPDATE calendar_waitlist_entry
                    SET queue_sequence = ?,
                        entry_revision = entry_revision + 1,
                        operation_request_hash = ?,
                        operation_payload_hash = ?,
                        updated_at = ?
                    WHERE id = ? AND plan_id = ?
                      AND activity_id IS NOT DISTINCT FROM ?
                      AND workspace_id = ?
                      AND source_created_by_user_id = ?
                      AND entry_state = 'WAITING'
                      AND entry_revision = ?
                    """,
                    sequence,
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    requested.entryId(),
                    target.planId(),
                    target.activityId(),
                    target.context().workspaceId(),
                    target.sourceOwnerId(),
                    requested.expectedRevision());
            if (changed != 1) {
                throw queueChanged();
            }
            long revision = requested.expectedRevision() + 1;
            resultRevision = Math.max(resultRevision, revision);
            reordered.add(new CalendarWaitlistReorderView.Entry(
                    requested.entryId(), sequence, revision));
            recordAudit(
                    requested.entryId(),
                    previous.sequence(),
                    sequence,
                    requested.expectedRevision(),
                    revision,
                    reason,
                    requestHash,
                    payloadHash,
                    now,
                    target);
        }
        recordReceipt(
                requestHash,
                payloadHash,
                resultRevision,
                now,
                target);
        return new CalendarWaitlistReorderView(
                target.planId(),
                target.scope(),
                List.copyOf(reordered),
                resultRevision);
    }

    private List<EntryRow> lockActiveEntries(
            CalendarRegistrationAccess.Target target) {
        return jdbc.query(
                """
                SELECT id, queue_sequence, entry_state, entry_revision
                FROM calendar_waitlist_entry
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND entry_state IN ('WAITING', 'OFFERED')
                ORDER BY queue_sequence, id
                FOR UPDATE
                """,
                (row, ignored) -> new EntryRow(
                        row.getObject("id", UUID.class),
                        row.getLong("queue_sequence"),
                        row.getString("entry_state"),
                        row.getLong("entry_revision")),
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
    }

    private SequenceBounds sequenceBounds(
            CalendarRegistrationAccess.Target target) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(queue_sequence), 0) AS maximum,
                       COALESCE(
                           MAX(queue_sequence) FILTER (
                               WHERE entry_state <> 'WAITING'),
                           0) AS non_waiting_maximum
                FROM calendar_waitlist_entry
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                """,
                (row, ignored) -> new SequenceBounds(
                        row.getLong("maximum"),
                        row.getLong("non_waiting_maximum")),
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
    }

    private CalendarWaitlistReorderView replayView(
            String requestHash,
            long resultRevision,
            CalendarRegistrationAccess.Target target) {
        List<CalendarWaitlistReorderView.Entry> entries = jdbc.query(
                """
                SELECT waitlist_entry_id, current_queue_sequence,
                       current_entry_revision
                FROM calendar_waitlist_reorder_audit
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND operation_request_hash = ?
                ORDER BY current_queue_sequence, waitlist_entry_id
                """,
                (row, ignored) -> new CalendarWaitlistReorderView.Entry(
                        row.getObject("waitlist_entry_id", UUID.class),
                        row.getLong("current_queue_sequence"),
                        row.getLong("current_entry_revision")),
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                requestHash);
        if (entries.isEmpty()) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_REORDER_REPLAY_UNAVAILABLE",
                    "The reorder receipt exists but its result requires append-only audit reconstruction");
        }
        return new CalendarWaitlistReorderView(
                target.planId(),
                target.scope(),
                List.copyOf(entries),
                resultRevision);
    }

    private void recordAudit(
            UUID entryId,
            long previousSequence,
            long currentSequence,
            long previousRevision,
            long currentRevision,
            String reason,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_waitlist_reorder_audit (
                    id, plan_id, activity_id, waitlist_entry_id,
                    previous_queue_sequence, current_queue_sequence,
                    previous_entry_revision, current_entry_revision,
                    reason, operation_request_hash,
                    operation_payload_hash, occurred_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                entryId,
                previousSequence,
                currentSequence,
                previousRevision,
                currentRevision,
                reason,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private Receipt receipt(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        List<Receipt> rows = jdbc.query(
                """
                SELECT operation_payload_hash, result_revision
                FROM calendar_registration_request_receipt
                WHERE request_kind = ?
                  AND operation_request_hash = ?
                  AND plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                """,
                (row, ignored) -> new Receipt(
                        row.getString("operation_payload_hash"),
                        row.getLong("result_revision")),
                RECEIPT_KIND,
                requestHash,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private void recordReceipt(
            String requestHash,
            String payloadHash,
            long resultRevision,
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
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REORDERED', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                RECEIPT_KIND,
                target.planId(),
                target.activityId(),
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "WAITLIST_REORDER|"
                                + target.planId()
                                + "|"
                                + target.activityId()
                                + "|"
                                + requestHash),
                resultRevision,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private static void requireCompleteWaitingSet(
            List<CalendarWaitlistReorderCommand.Entry> requested,
            List<EntryRow> current) {
        if (requested.size() != current.size()) {
            throw queueChanged();
        }
        var requestedIds = new HashSet<UUID>();
        for (CalendarWaitlistReorderCommand.Entry entry : requested) {
            if (!requestedIds.add(entry.entryId())) {
                throw new IllegalArgumentException(
                        "A waitlist entry may appear only once");
            }
        }
        var currentById = new java.util.HashMap<UUID, EntryRow>();
        current.forEach(row -> currentById.put(row.id(), row));
        for (CalendarWaitlistReorderCommand.Entry entry : requested) {
            EntryRow row = currentById.get(entry.entryId());
            if (row == null
                    || !"WAITING".equals(row.state())
                    || row.revision() != entry.expectedRevision()) {
                throw queueChanged();
            }
        }
    }

    private static void requireCommand(
            CalendarWaitlistReorderCommand command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.orderedEntries() == null
                || command.orderedEntries().isEmpty()
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().strip().length() > 500) {
            throw new IllegalArgumentException(
                    "A scoped waitlist order, bounded reason, and request are required");
        }
        for (CalendarWaitlistReorderCommand.Entry entry :
                command.orderedEntries()) {
            if (entry == null
                    || entry.entryId() == null
                    || entry.expectedRevision() < 1) {
                throw new IllegalArgumentException(
                        "Every waitlist entry requires an identity and revision");
            }
        }
        CalendarParticipationAccess.hash(command.requestId());
    }

    private static String payloadHash(
            CalendarWaitlistReorderCommand command, String reason) {
        String ordered = command.orderedEntries().stream()
                .map(entry -> entry.entryId()
                        + "@"
                        + entry.expectedRevision())
                .collect(java.util.stream.Collectors.joining(","));
        return CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + ordered
                        + "|"
                        + reason);
    }

    private static void requirePayload(
            String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The waitlist reorder request key was used for another order");
        }
    }

    private static BusinessException queueChanged() {
        return new BusinessException(
                "CALENDAR_WAITLIST_REORDER_REVISION_CONFLICT",
                "The waitlist changed; reload the complete queue before reordering");
    }

    private record EntryRow(
            UUID id, long sequence, String state, long revision) {}

    private record Receipt(String payloadHash, long resultRevision) {}

    private record SequenceBounds(
            long maximum, long nonWaitingMaximum) {}
}
