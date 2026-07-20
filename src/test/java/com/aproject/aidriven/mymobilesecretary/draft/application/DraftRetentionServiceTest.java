package com.aproject.aidriven.mymobilesecretary.draft.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import com.aproject.aidriven.mymobilesecretary.draft.persistence.DraftRetentionBindingRepository;
import com.aproject.aidriven.mymobilesecretary.draft.persistence.DraftRetentionPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ProductObservationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.BankTransferDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DraftRetentionServiceTest {
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void registerCreatesActorBindingAndSynchronizesDomainExpiry() {
        Dependencies d = dependencies();
        BankTransferDraft bank = mock(BankTransferDraft.class);
        when(d.preferences.findByCreatedByUserId(ACTOR)).thenReturn(Optional.empty());
        when(d.preferences.save(any())).thenAnswer(call -> call.getArgument(0));
        when(d.bankDrafts.findById(8L)).thenReturn(Optional.of(bank));
        when(d.bindings.save(any())).thenAnswer(call -> call.getArgument(0));

        DraftRetentionBinding binding;
        try (var ignored = actorScope()) {
            binding = d.service.register(DraftType.BANK_TRANSFER, 8L, "轉帳草稿");
        }

        assertThat(binding.getExpiresAt()).isEqualTo(Instant.parse("2030-01-08T16:00:00Z"));
        verify(bank).extendRetention(NOW, binding.getExpiresAt());
        verify(d.bindings).save(binding);
    }

    @Test
    void dueReminderIsEnqueuedAndMarkedOnlyAfterDurableOutboxInsert() {
        Dependencies d = dependencies();
        DraftRetentionBinding binding = mock(DraftRetentionBinding.class);
        when(binding.getDraftType()).thenReturn(DraftType.PRODUCT_OBSERVATION);
        when(binding.getDraftId()).thenReturn(9L);
        when(binding.getTitle()).thenReturn("油漆草稿");
        when(binding.getRemindAt()).thenReturn(NOW.minusSeconds(1));
        when(binding.getExpiresAt()).thenReturn(Instant.parse("2030-01-01T16:00:00Z"));
        when(d.bindings.findByCreatedByUserIdAndNotifiedAtIsNullAndRemindAtLessThanEqual(
                ACTOR, NOW)).thenReturn(List.of(binding));
        when(d.notifications.enqueue(any())).thenReturn(1);

        int sent;
        try (var ignored = actorScope()) {
            sent = d.service.notifyExpiringDrafts(NOW);
        }

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(d.notifications).enqueue(request.capture());
        assertThat(request.getValue().message()).contains("油漆草稿", "24:00");
        verify(binding).markNotified(NOW);
        verify(d.bindings).save(binding);
    }

    @Test
    void midnightCleanupDeletesOnlyBoundDomainDraftAndBinding() {
        Dependencies d = dependencies();
        DraftRetentionBinding binding = mock(DraftRetentionBinding.class);
        when(binding.getDraftType()).thenReturn(DraftType.BANK_TRANSFER);
        when(binding.getDraftId()).thenReturn(10L);
        when(d.bindings.findByCreatedByUserIdAndExpiresAtLessThanEqual(ACTOR, NOW))
                .thenReturn(List.of(binding));

        int deleted;
        try (var ignored = actorScope()) {
            deleted = d.service.deleteExpiredDrafts(NOW);
        }

        assertThat(deleted).isEqualTo(1);
        verify(d.bankDrafts).deleteById(10L);
        verify(d.bindings).delete(binding);
    }

    private static Dependencies dependencies() {
        DraftRetentionPreferenceRepository preferences = mock(DraftRetentionPreferenceRepository.class);
        DraftRetentionBindingRepository bindings = mock(DraftRetentionBindingRepository.class);
        BankTransferDraftRepository bankDrafts = mock(BankTransferDraftRepository.class);
        ProductObservationDraftRepository productDrafts = mock(ProductObservationDraftRepository.class);
        NotificationPublisher notifications = mock(NotificationPublisher.class);
        DraftRetentionService service = new DraftRetentionService(
                preferences, bindings, bankDrafts, productDrafts, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Dependencies(service, preferences, bindings, bankDrafts,
                productDrafts, notifications);
    }

    private static WorkspaceContextHolder.Scope actorScope() {
        return WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE));
    }

    private record Dependencies(
            DraftRetentionService service,
            DraftRetentionPreferenceRepository preferences,
            DraftRetentionBindingRepository bindings,
            BankTransferDraftRepository bankDrafts,
            ProductObservationDraftRepository productDrafts,
            NotificationPublisher notifications) {}
}
