package com.aproject.aidriven.mymobilesecretary.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft.Status;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.BankTransferDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BankTransferServiceTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void recognizesBankMaskWithoutTreatingCompleteCompanyAsMasked() {
        assertThat(BankTransferService.isMaskedRecipient("o迎新淨化科技有限公")).isTrue();
        assertThat(BankTransferService.isMaskedRecipient("○迎新淨化科技有限公")).isTrue();
        assertThat(BankTransferService.isMaskedRecipient("迎新淨化科技有限公司")).isFalse();
    }

    @Test
    void fullCompanyReplyCompletesPendingDraftExactlyOnce() {
        BankTransferDraftRepository repository = mock(BankTransferDraftRepository.class);
        PriceRecordService prices = mock(PriceRecordService.class);
        UniversalLifeRecordService life = mock(UniversalLifeRecordService.class);
        BankTransferDraft draft = BankTransferDraft.pending(
                "o迎新淨化科技有限公", "訂金", 3000, LocalDate.of(2026, 7, 18),
                NOW.plusSeconds(3600), NOW.minusSeconds(60));
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                ACTOR, Status.PENDING_RECIPIENT, NOW)).thenReturn(Optional.of(draft));
        BankTransferService service = new BankTransferService(repository, prices, life,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicBoolean mutationStarted = new AtomicBoolean();

        Optional<IntentResult> result;
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            result = service.answer(
                    "完整收款公司是迎新淨化科技有限公司", () -> mutationStarted.set(true));
        }

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().action())
                .isEqualTo(IntentResult.Action.TRANSFER_PAYMENT_IMPORTED);
        assertThat(result.orElseThrow().message()).contains("迎新淨化科技有限公司", "3,000");
        assertThat(mutationStarted).isTrue();
        assertThat(draft.getStatus()).isEqualTo(Status.COMPLETED);
        verify(prices).record("訂金", "迎新淨化科技有限公司", 3000, 1,
                LocalDate.of(2026, 7, 18));
        verify(repository).save(draft);
    }

    @Test
    void unrelatedReplyDoesNotConsumePendingDraft() {
        BankTransferDraftRepository repository = mock(BankTransferDraftRepository.class);
        PriceRecordService prices = mock(PriceRecordService.class);
        BankTransferService service = new BankTransferService(repository, prices,
                mock(UniversalLifeRecordService.class), Clock.fixed(NOW, ZoneOffset.UTC));

        Optional<IntentResult> result = service.answer("你沒有聽懂", () -> { });

        assertThat(result).isEmpty();
        verify(repository, never())
                .findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        any(), any(), any());
        verify(prices, never()).record(any(), any(), any(Integer.class), any(Integer.class), any());
    }
}
