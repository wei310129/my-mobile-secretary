package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationDeliveryState;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationOutboxService;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarParticipationNotificationProcessor {

    private final JdbcTemplate jdbc;
    private final NotificationPublisher notifications;
    private final NotificationOutboxService notificationOutbox;
    private final CalendarParticipationNotificationMessageFactory messages;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxBatch;

    public CalendarParticipationNotificationProcessor(
            JdbcTemplate jdbc,
            NotificationPublisher notifications,
            NotificationOutboxService notificationOutbox,
            CalendarParticipationNotificationMessageFactory messages,
            Clock clock,
            @Value("${app.calendar.participation-notification.retry-delay:30s}")
                    Duration retryDelay,
            @Value("${app.calendar.participation-notification.max-batch:50}")
                    int maxBatch) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.notificationOutbox = notificationOutbox;
        this.messages = messages;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxBatch = maxBatch;
    }

    @Transactional
    public int process(UUID actorId) {
        WorkspaceContext context = requireActor(actorId);
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                "calendar-participation-notification|"
                        + context.workspaceId()
                        + "|"
                        + actorId);
        Instant now = Instant.now(clock);
        List<PendingDelivery> pending = jdbc.query(
                """
                SELECT id, receipt_id, event_type, source_revision
                FROM calendar_participation_outbox
                WHERE workspace_id = ?
                  AND created_by_user_id = ?
                  AND delivery_status IN ('PENDING', 'FAILED')
                  AND (
                    next_delivery_attempt_at IS NULL
                    OR next_delivery_attempt_at <= ?)
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                CalendarParticipationNotificationProcessor::pendingDelivery,
                context.workspaceId(),
                actorId,
                Timestamp.from(now),
                maxBatch);
        int processed = 0;
        for (PendingDelivery delivery : pending) {
            reconcile(delivery, context, now);
            processed++;
        }
        return processed;
    }

    private void reconcile(
            PendingDelivery delivery,
            WorkspaceContext context,
            Instant now) {
        String deliveryKey =
                CalendarParticipationNotificationMessageFactory.deliveryKey(
                        delivery.id());
        NotificationDeliveryState state =
                notificationOutbox.deliveryState(
                        context.actorId(), deliveryKey);
        if (state.status()
                == NotificationDeliveryState.Status.NOT_FOUND) {
            notifications.enqueue(messages.create(
                    context.actorId(),
                    delivery.id(),
                    delivery.eventType(),
                    delivery.sourceRevision()));
            state = notificationOutbox.deliveryState(
                    context.actorId(), deliveryKey);
            if (state.status()
                    == NotificationDeliveryState.Status.NOT_FOUND) {
                markFailed(
                        delivery,
                        now,
                        1,
                        now.plus(retryDelay),
                        "NO_DELIVERY_DESTINATION");
                return;
            }
        }
        switch (state.status()) {
            case IN_FLIGHT -> markPending(
                    delivery,
                    now,
                    state.attemptCount(),
                    state.nextAttemptAt() == null
                            ? now.plus(retryDelay)
                            : state.nextAttemptAt());
            case SENT -> markSent(
                    delivery,
                    now,
                    state.attemptCount(),
                    state.sentAt() == null ? now : state.sentAt());
            case FAILED -> markFailed(
                    delivery,
                    now,
                    state.attemptCount(),
                    null,
                    state.lastError());
            case NOT_FOUND -> throw new IllegalStateException(
                    "Notification state remained unresolved");
        }
    }

    private void markPending(
            PendingDelivery delivery,
            Instant now,
            int attempts,
            Instant nextAttemptAt) {
        updateOutbox(
                delivery,
                "PENDING",
                attempts,
                nextAttemptAt,
                null,
                null,
                now);
        updateReceipt(
                delivery,
                "PENDING",
                attempts,
                nextAttemptAt,
                null,
                null,
                now);
    }

    private void markSent(
            PendingDelivery delivery,
            Instant now,
            int attempts,
            Instant sentAt) {
        updateOutbox(
                delivery,
                "SENT",
                attempts,
                null,
                sentAt,
                null,
                now);
        updateReceipt(
                delivery,
                "SENT",
                attempts,
                null,
                sentAt,
                null,
                now);
    }

    private void markFailed(
            PendingDelivery delivery,
            Instant now,
            int attempts,
            Instant nextAttemptAt,
            String errorCode) {
        updateOutbox(
                delivery,
                "FAILED",
                attempts,
                nextAttemptAt,
                null,
                bounded(errorCode),
                now);
        updateReceipt(
                delivery,
                "FAILED",
                attempts,
                nextAttemptAt,
                null,
                bounded(errorCode),
                now);
    }

    private void updateOutbox(
            PendingDelivery delivery,
            String status,
            int attempts,
            Instant nextAttemptAt,
            Instant deliveredAt,
            String failure,
            Instant now) {
        jdbc.update(
                """
                UPDATE calendar_participation_outbox
                SET delivery_status = ?,
                    delivery_attempt_count =
                        GREATEST(delivery_attempt_count, ?),
                    next_delivery_attempt_at = ?,
                    delivered_at = ?,
                    last_delivery_failure = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                status,
                attempts,
                timestamp(nextAttemptAt),
                timestamp(deliveredAt),
                failure,
                Timestamp.from(now),
                delivery.id());
    }

    private void updateReceipt(
            PendingDelivery delivery,
            String status,
            int attempts,
            Instant nextAttemptAt,
            Instant deliveredAt,
            String failure,
            Instant now) {
        if (delivery.receiptId() == null) {
            return;
        }
        jdbc.update(
                """
                UPDATE calendar_authoritative_recipient_receipt
                SET delivery_status = ?,
                    delivery_attempt_count =
                        GREATEST(delivery_attempt_count, ?),
                    next_delivery_attempt_at = ?,
                    delivered_at = ?,
                    last_delivery_failure = ?,
                    receipt_revision = receipt_revision + 1,
                    updated_at = ?
                WHERE id = ?
                """,
                status,
                attempts,
                timestamp(nextAttemptAt),
                timestamp(deliveredAt),
                failure,
                Timestamp.from(now),
                delivery.receiptId());
    }

    private WorkspaceContext requireActor(UUID actorId) {
        WorkspaceContext context =
                WorkspaceContextHolder.requireContext();
        if (!context.actorId().equals(actorId)) {
            throw new IllegalArgumentException(
                    "Calendar notification actor context mismatch");
        }
        return context;
    }

    private static PendingDelivery pendingDelivery(ResultSet rs, int row)
            throws SQLException {
        return new PendingDelivery(
                rs.getObject("id", UUID.class),
                rs.getObject("receipt_id", UUID.class),
                rs.getString("event_type"),
                rs.getLong("source_revision"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "DELIVERY_FAILED";
        }
        String stripped = value.strip();
        return stripped.length() <= 500
                ? stripped
                : stripped.substring(0, 500);
    }

    private record PendingDelivery(
            UUID id,
            UUID receiptId,
            String eventType,
            long sourceRevision) {
    }
}
