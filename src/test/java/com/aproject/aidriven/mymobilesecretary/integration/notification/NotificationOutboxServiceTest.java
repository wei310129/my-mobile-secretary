package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import com.aproject.aidriven.mymobilesecretary.shared.time.TimeDisplayPreferenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
    private static final UUID TARGET =
            UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE =
            UUID.fromString("30000000-0000-0000-0000-000000000101");

    @Mock private NotificationOutboxRepository repository;
    @Mock private ReminderDeliveryRepository deliveryRepository;
    @Mock private TimeDisplayPreferenceService timeDisplayPreferenceService;

    @Test
    void claimAndAcknowledgeUseFencedTokenAndClearPayload() {
        NotificationOutbox entry = NotificationOutboxTest.pending(true);
        when(repository.findClaimable(TARGET, NotificationOutboxStatus.PENDING, NOW,
                PageRequest.of(0, 2))).thenReturn(List.of(entry));
        when(timeDisplayPreferenceService.applyToHumanText(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        NotificationOutboxService service = service(2);

        NotificationOutboxService.ClaimedNotification claim = service.claimDue(TARGET).getFirst();
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
        when(repository.findExpiredClaims(TARGET, NotificationOutboxStatus.SENDING, NOW,
                PageRequest.of(0, 2))).thenReturn(List.of(entry));

        assertThat(service(2).recoverExpiredClaims(TARGET)).isEqualTo(1);
        assertThat(entry.getStatus()).isEqualTo(NotificationOutboxStatus.DEAD_LETTER);
        assertThat(entry.getMessage()).isNull();
    }

    @Test
    void claimedNotificationUsesRecipientHumanTimePreference() {
        NotificationOutbox entry = NotificationOutboxTest.pending(true);
        ReflectionTestUtils.setField(entry, "message", "19:00 記得出門");
        when(repository.findClaimable(TARGET, NotificationOutboxStatus.PENDING, NOW,
                PageRequest.of(0, 2))).thenReturn(List.of(entry));
        when(timeDisplayPreferenceService.applyToHumanText("看醫生"))
                .thenReturn("看醫生");
        when(timeDisplayPreferenceService.applyToHumanText("19:00 記得出門"))
                .thenReturn("下午 7:00 記得出門");

        ReminderNotification notification = service(2).claimDue(TARGET).getFirst().envelope();

        assertThat(notification.message()).contains("下午 7:00").doesNotContain("19:00");
    }

    @Test
    void deliveryStateIsNotFoundWhenStableKeyHasNoEnvelope() {
        when(repository.findAllByWorkspaceIdAndTargetUserIdAndDeliveryKeyOrderByChannel(
                        WORKSPACE, TARGET, "calendar:missing"))
                .thenReturn(List.of());

        assertThat(deliveryState("calendar:missing").status())
                .isEqualTo(NotificationDeliveryState.Status.NOT_FOUND);
    }

    @Test
    void deliveryStateRemainsInFlightWhileAnyChannelIsPending() {
        NotificationOutbox pending = NotificationOutboxTest.pending(true);
        NotificationOutbox sent = NotificationOutboxTest.pending(true);
        sent.claim(UUID.randomUUID(), NOW.plusSeconds(30));
        sent.markSent(NOW.plusSeconds(1));
        when(repository.findAllByWorkspaceIdAndTargetUserIdAndDeliveryKeyOrderByChannel(
                        WORKSPACE, TARGET, "calendar:in-flight"))
                .thenReturn(List.of(pending, sent));

        assertThat(deliveryState("calendar:in-flight").status())
                .isEqualTo(NotificationDeliveryState.Status.IN_FLIGHT);
    }

    @Test
    void deliveryStateIsSentOnlyWhenEveryChannelWasSent() {
        NotificationOutbox first = NotificationOutboxTest.pending(true);
        NotificationOutbox second = NotificationOutboxTest.pending(true);
        first.claim(UUID.randomUUID(), NOW.plusSeconds(30));
        first.markSent(NOW.plusSeconds(1));
        second.claim(UUID.randomUUID(), NOW.plusSeconds(30));
        second.markSent(NOW.plusSeconds(2));
        when(repository.findAllByWorkspaceIdAndTargetUserIdAndDeliveryKeyOrderByChannel(
                        WORKSPACE, TARGET, "calendar:sent"))
                .thenReturn(List.of(first, second));

        NotificationDeliveryState state = deliveryState("calendar:sent");

        assertThat(state.status()).isEqualTo(NotificationDeliveryState.Status.SENT);
        assertThat(state.sentAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void partialMultiChannelDeadLetterIsVisibleAsFailure() {
        NotificationOutbox sent = NotificationOutboxTest.pending(true);
        sent.claim(UUID.randomUUID(), NOW.plusSeconds(30));
        sent.markSent(NOW.plusSeconds(1));
        NotificationOutbox failed = NotificationOutboxTest.pending(true);
        failed.claim(UUID.randomUUID(), NOW.plusSeconds(30));
        failed.deadLetter(NOW.plusSeconds(2), "PROVIDER_REJECTED");
        when(repository.findAllByWorkspaceIdAndTargetUserIdAndDeliveryKeyOrderByChannel(
                        WORKSPACE, TARGET, "calendar:failed"))
                .thenReturn(List.of(sent, failed));

        NotificationDeliveryState state = deliveryState("calendar:failed");

        assertThat(state.status()).isEqualTo(NotificationDeliveryState.Status.FAILED);
        assertThat(state.lastError()).isEqualTo("PROVIDER_REJECTED");
    }

    private NotificationDeliveryState deliveryState(String key) {
        try (var ignored =
                WorkspaceContextHolder.open(
                        new WorkspaceContext(TARGET, WORKSPACE, WorkspaceChannel.TEST))) {
            return service(2).deliveryState(TARGET, key);
        }
    }

    private NotificationOutboxService service(int maxBatch) {
        return new NotificationOutboxService(repository, deliveryRepository,
                new NotificationOutboxProperties(
                        Duration.ofMinutes(2), Duration.ofSeconds(30), 5, maxBatch),
                Clock.fixed(NOW, ZoneOffset.UTC), timeDisplayPreferenceService);
    }
}
