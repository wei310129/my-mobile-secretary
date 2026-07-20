package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationOutboxTest {

    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void sentTransitionClearsSensitivePayloadButKeepsDeliveryMetadata() {
        NotificationOutbox entry = pending(true);
        UUID token = UUID.randomUUID();
        entry.claim(token, NOW.plusSeconds(60));

        ReminderNotification envelope = entry.envelope(UnaryOperator.identity());
        entry.markSent(NOW.plusSeconds(1));

        assertThat(envelope.title()).isEqualTo("看醫生");
        assertThat(entry.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(entry.getTitle()).isNull();
        assertThat(entry.getMessage()).isNull();
        assertThat(entry.getTerminalAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void retryRetainsPayloadWhileDeadLetterErasesIt() {
        NotificationOutbox retrying = pending(true);
        retrying.claim(UUID.randomUUID(), NOW.plusSeconds(10));
        retrying.retry(NOW.plusSeconds(30), "TEMPORARY_FAILURE");
        assertThat(retrying.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(retrying.getTitle()).isNotNull();

        retrying.claim(UUID.randomUUID(), NOW.plusSeconds(40));
        retrying.deadLetter(NOW.plusSeconds(41), "MAX_ATTEMPTS");
        assertThat(retrying.getStatus()).isEqualTo(NotificationOutboxStatus.DEAD_LETTER);
        assertThat(retrying.getTitle()).isNull();
        assertThat(retrying.getMessage()).isNull();
    }

    static NotificationOutbox pending(boolean retrySafe) {
        NotificationOutbox entry = new NotificationOutbox();
        ReflectionTestUtils.setField(entry, "id", 7L);
        ReflectionTestUtils.setField(entry, "workspaceId",
                UUID.fromString("40000000-0000-0000-0000-000000000101"));
        ReflectionTestUtils.setField(entry, "deliveryId", UUID.randomUUID());
        ReflectionTestUtils.setField(entry, "deliveryKey", "schedule:7");
        ReflectionTestUtils.setField(entry, "targetUserId",
                UUID.fromString("40000000-0000-0000-0000-000000000001"));
        ReflectionTestUtils.setField(entry, "channel", "LOG");
        ReflectionTestUtils.setField(entry, "destination", "server-log");
        ReflectionTestUtils.setField(entry, "reminderId", 9L);
        ReflectionTestUtils.setField(entry, "taskId", 8L);
        ReflectionTestUtils.setField(entry, "title", "看醫生");
        ReflectionTestUtils.setField(entry, "message", "記得提前出門");
        ReflectionTestUtils.setField(entry, "retrySafe", retrySafe);
        ReflectionTestUtils.setField(entry, "status", NotificationOutboxStatus.PENDING);
        ReflectionTestUtils.setField(entry, "attemptCount", 0);
        ReflectionTestUtils.setField(entry, "availableAt", NOW);
        ReflectionTestUtils.setField(entry, "createdAt", NOW);
        return entry;
    }
}
