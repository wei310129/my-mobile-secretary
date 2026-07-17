package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists tenant-bound notification envelopes in the same transaction as the business event. */
@Service
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final List<NotificationSender> senders;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public NotificationPublisher(List<NotificationSender> senders, JdbcTemplate jdbcTemplate, Clock clock) {
        this.senders = List.copyOf(senders);
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public int enqueue(NotificationRequest request) {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        UUID targetUserId = request.targetUserId() == null
                ? scope.actorId() : request.targetUserId();
        Instant now = Instant.now(clock);
        int inserted = 0;
        for (NotificationSender sender : senders) {
            NotificationChannel channel;
            String destination;
            boolean retrySafe;
            try {
                channel = sender.channel();
                if (channel == null) {
                    throw new IllegalStateException("notification channel is required");
                }
                var resolved = sender.destinationFor(scope.workspaceId(), targetUserId);
                if (resolved.isEmpty()) {
                    log.warn("Notification destination unavailable [channel={}, workspace={}, target={}]",
                            channel,
                            SensitiveValueFingerprint.of(scope.workspaceId().toString()),
                            SensitiveValueFingerprint.of(targetUserId.toString()));
                    continue;
                }
                destination = resolved.get();
                retrySafe = sender.supportsStableDeliveryId();
            } catch (RuntimeException failure) {
                log.warn("Notification enqueue skipped [sender={}, cause={}]",
                        sender.getClass().getSimpleName(), failure.getClass().getSimpleName());
                continue;
            }
            String channelName = channel.name();
            UUID deliveryId = stableDeliveryId(scope.workspaceId(), request.deliveryKey(), channelName);
            // Persistence failures deliberately escape so the surrounding business transaction
            // rolls back instead of committing an event with no durable notification.
            inserted += jdbcTemplate.update("""
                    INSERT INTO notification_outbox (
                        workspace_id, created_by_user_id, delivery_id, delivery_key,
                        target_user_id, channel, destination, reminder_id, task_id,
                        title, message, retry_safe, status, attempt_count, available_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    ON CONFLICT (workspace_id, delivery_key, channel) DO NOTHING
                    """, scope.workspaceId(), scope.actorId(), deliveryId, request.deliveryKey(),
                    targetUserId, channelName, destination, request.reminderId(),
                    request.taskId(), request.title(), request.message(), retrySafe, now, now);
        }
        return inserted;
    }

    static UUID stableDeliveryId(UUID workspaceId, String deliveryKey, String channel) {
        String material = "notification|" + workspaceId + "|" + deliveryKey + "|"
                + channel.toUpperCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
