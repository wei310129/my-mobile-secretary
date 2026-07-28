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
public class CalendarRegistrationNotificationProcessor {

    private final JdbcTemplate jdbc;
    private final NotificationPublisher notifications;
    private final NotificationOutboxService notificationOutbox;
    private final CalendarRegistrationNotificationMessageFactory messages;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxBatch;

    public CalendarRegistrationNotificationProcessor(
            JdbcTemplate jdbc,
            NotificationPublisher notifications,
            NotificationOutboxService notificationOutbox,
            CalendarRegistrationNotificationMessageFactory messages,
            Clock clock,
            @Value("${app.calendar.registration-notification.retry-delay:30s}")
                    Duration retryDelay,
            @Value("${app.calendar.registration-notification.max-batch:50}")
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
                "calendar-registration-notification|"
                        + context.workspaceId()
                        + "|"
                        + actorId);
        Instant now = Instant.now(clock);
        List<PendingDelivery> pending = jdbc.query(
                """
                SELECT id, event_type, payload_text
                FROM calendar_registration_outbox
                WHERE workspace_id = ?
                  AND recipient_user_id = ?
                  AND (
                    delivery_status = 'PENDING'
                    OR (
                        delivery_status = 'FAILED'
                        AND next_delivery_attempt_at IS NOT NULL
                        AND next_delivery_attempt_at <= ?))
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                CalendarRegistrationNotificationProcessor::pendingDelivery,
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
                CalendarRegistrationNotificationMessageFactory.deliveryKey(
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
                    delivery.payloadText()));
            state = notificationOutbox.deliveryState(
                    context.actorId(), deliveryKey);
            if (state.status()
                    == NotificationDeliveryState.Status.NOT_FOUND) {
                updateOutbox(
                        delivery,
                        context,
                        "FAILED",
                        1,
                        now.plus(retryDelay),
                        null,
                        "NO_DELIVERY_DESTINATION",
                        now);
                return;
            }
        }
        switch (state.status()) {
            case IN_FLIGHT -> updateOutbox(
                    delivery,
                    context,
                    "PENDING",
                    state.attemptCount(),
                    state.nextAttemptAt() == null
                            ? now.plus(retryDelay)
                            : state.nextAttemptAt(),
                    null,
                    null,
                    now);
            case SENT -> updateOutbox(
                    delivery,
                    context,
                    "SENT",
                    state.attemptCount(),
                    null,
                    state.sentAt() == null ? now : state.sentAt(),
                    null,
                    now);
            case FAILED -> updateOutbox(
                    delivery,
                    context,
                    "FAILED",
                    state.attemptCount(),
                    null,
                    null,
                    bounded(state.lastError()),
                    now);
            case NOT_FOUND -> throw new IllegalStateException(
                    "Notification state remained unresolved");
        }
    }

    private void updateOutbox(
            PendingDelivery delivery,
            WorkspaceContext context,
            String status,
            int attempts,
            Instant nextAttemptAt,
            Instant deliveredAt,
            String failure,
            Instant now) {
        jdbc.update(
                """
                UPDATE calendar_registration_outbox
                SET delivery_status = ?,
                    delivery_attempt_count =
                        GREATEST(delivery_attempt_count, ?),
                    next_delivery_attempt_at = ?,
                    delivered_at = ?,
                    last_delivery_failure = ?,
                    updated_at = ?
                WHERE id = ?
                  AND workspace_id = ?
                  AND recipient_user_id = ?
                """,
                status,
                attempts,
                timestamp(nextAttemptAt),
                timestamp(deliveredAt),
                failure,
                Timestamp.from(now),
                delivery.id(),
                context.workspaceId(),
                context.actorId());
    }

    private WorkspaceContext requireActor(UUID actorId) {
        WorkspaceContext context =
                WorkspaceContextHolder.requireContext();
        if (!context.actorId().equals(actorId)) {
            throw new IllegalArgumentException(
                    "Calendar registration notification actor context mismatch");
        }
        return context;
    }

    private static PendingDelivery pendingDelivery(ResultSet rs, int row)
            throws SQLException {
        return new PendingDelivery(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload_text"));
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
            String eventType,
            String payloadText) {
    }
}
