package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Mock private NotificationOutboxRepository repository;
    @Mock private ReminderDeliveryRepository deliveryRepository;

    @Test
    void claimAndAcknowledgeUseFencedTokenAndClearPayload() {
        NotificationOutbox entry = NotificationOutboxTest.pending(true);
        when(repository.findClaimable(NotificationOutboxStatus.PENDING, NOW,
                PageRequest.of(0, 2))).thenReturn(List.of(entry));
        NotificationOutboxService service = service(2);

        NotificationOutboxService.ClaimedNotification claim = service.claimDue().getFirst();
        when(repository.findById(claim.id())).thenReturn(Optional.of(entry));

        assertThat(service.markSent(claim.id(), claim.claimToken())).isTrue();
        assertThat(entry.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(entry.getMessage()).isNull();
        verify(deliveryRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredNonIdempotentDeliveryBecomesDeadLetterWithoutRetry() {
        NotificationOutbox entry = NotificationOutboxTest.pending(false);
        entry.claim(java.util.UUID.randomUUID(), NOW.minusSeconds(1));
        when(repository.findExpiredClaims(NotificationOutboxStatus.SENDING, NOW,
                PageRequest.of(0, 2))).thenReturn(List.of(entry));

        assertThat(service(2).recoverExpiredClaims()).isEqualTo(1);
        assertThat(entry.getStatus()).isEqualTo(NotificationOutboxStatus.DEAD_LETTER);
        assertThat(entry.getMessage()).isNull();
    }

    private NotificationOutboxService service(int maxBatch) {
        return new NotificationOutboxService(repository, deliveryRepository,
                new NotificationOutboxProperties(
                        Duration.ofMinutes(2), Duration.ofSeconds(30), 5, maxBatch),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
