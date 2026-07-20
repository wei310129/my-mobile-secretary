package com.aproject.aidriven.mymobilesecretary.payment.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.draft.application.DraftRetentionService;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft;
import com.aproject.aidriven.mymobilesecretary.payment.domain.BankTransferDraft.Status;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.BankTransferDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic bank-transfer draft and completion flow; masked recipients never become merchants. */
@Service
@Transactional
public class BankTransferService {

    private static final Pattern MASK_SYMBOL = Pattern.compile("[○●*＊Ｘ]");
    private static final Pattern PREFIX_MASK = Pattern.compile("^[oO0Xx][\\p{IsHan}].*");
    private static final Pattern COMPLETE_ORGANIZATION = Pattern.compile(
            ".*(?:股份有限公司|有限公司|公司|商行|行號|工作室|診所|醫院)$");

    private final BankTransferDraftRepository repository;
    private final PriceRecordService priceRecordService;
    private final UniversalLifeRecordService lifeRecordService;
    private final Clock clock;
    private DraftRetentionService draftRetention;

    public BankTransferService(BankTransferDraftRepository repository,
                               PriceRecordService priceRecordService,
                               UniversalLifeRecordService lifeRecordService,
                               Clock clock) {
        this.repository = repository;
        this.priceRecordService = priceRecordService;
        this.lifeRecordService = lifeRecordService;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDraftRetention(DraftRetentionService draftRetention) {
        this.draftRetention = draftRetention;
    }

    public DraftView createMaskedDraft(String displayedRecipient, String purpose,
                                       Integer amountTwd, LocalDate transferredAt) {
        if (!isMaskedRecipient(displayedRecipient)) {
            throw new IllegalArgumentException("recipient is not masked");
        }
        String safePurpose = purpose == null || purpose.isBlank() ? "轉帳" : purpose.strip();
        Instant now = Instant.now(clock);
        BankTransferDraft saved = repository.save(BankTransferDraft.pending(
                displayedRecipient, safePurpose, amountTwd, transferredAt,
                DraftRetentionService.expiresAt(now), now));
        if (draftRetention != null) {
            draftRetention.register(DraftType.BANK_TRANSFER, saved.getId(), "轉帳：" + safePurpose);
        }
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.PURCHASE,
                "建立待補收款人的轉帳草稿", now, List.of("轉帳", safePurpose));
        return DraftView.from(saved, retentionDisclosure(saved));
    }

    public void recordComplete(String recipient, String purpose, int amountTwd,
                               LocalDate transferredAt) {
        requireCompleteRecipient(recipient);
        if (amountTwd <= 0 || transferredAt == null) {
            throw new IllegalArgumentException("transfer amount and date are required");
        }
        String safePurpose = purpose == null || purpose.isBlank() ? "轉帳" : purpose.strip();
        priceRecordService.record(safePurpose, recipient.strip(), amountTwd, 1, transferredAt);
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.PURCHASE,
                "完成一筆銀行轉帳消費", transferredAt.atStartOfDay(
                        java.time.ZoneId.of("Asia/Taipei")).toInstant(),
                List.of("轉帳", safePurpose, recipient.strip()));
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String candidate = normalizeCandidate(text);
        if (candidate == null) return Optional.empty();
        Instant now = Instant.now(clock);
        Optional<BankTransferDraft> pending = repository
                .findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId(),
                        Status.PENDING_RECIPIENT, now);
        if (pending.isEmpty()) return Optional.empty();
        requireCompleteRecipient(candidate);
        BankTransferDraft draft = pending.get();
        beforeMutation.run();
        draft.confirmRecipient(candidate, now);
        if (draft.getAmountTwd() == null || draft.getTransferredAt() == null) {
            String disclosure;
            if (draftRetention == null) {
                draft.extendRetention(now, DraftRetentionService.expiresAt(now));
                disclosure = fallbackDisclosure(draft.getExpiresAt());
            } else {
                disclosure = draftRetention.touch(DraftType.BANK_TRANSFER, draft.getId());
            }
            repository.save(draft);
            return Optional.of(IntentResult.message(IntentResult.Action.CLARIFICATION_NEEDED,
                    "已補上完整收款人「%s」，但轉帳日期或實際金額仍不完整，因此尚未建立消費紀錄。"
                            .formatted(candidate) + "\n" + disclosure));
        }
        priceRecordService.record(draft.getPurpose(), candidate, draft.getAmountTwd(), 1,
                draft.getTransferredAt());
        draft.complete(now);
        repository.save(draft);
        if (draftRetention != null) {
            draftRetention.complete(DraftType.BANK_TRANSFER, draft.getId());
        }
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.PURCHASE,
                "完成一筆銀行轉帳消費", now,
                List.of("轉帳", draft.getPurpose(), candidate));
        return Optional.of(IntentResult.message(IntentResult.Action.TRANSFER_PAYMENT_IMPORTED,
                "已用完整收款人「%s」建立%s消費紀錄：NT$ %,d，日期 %s。"
                        .formatted(candidate, draft.getPurpose(), draft.getAmountTwd(),
                                draft.getTransferredAt())));
    }

    public static boolean isMaskedRecipient(String value) {
        if (value == null || value.isBlank()) return true;
        String clean = value.strip();
        return MASK_SYMBOL.matcher(clean).find() || PREFIX_MASK.matcher(clean).matches();
    }

    private static void requireCompleteRecipient(String recipient) {
        if (recipient == null || recipient.isBlank() || isMaskedRecipient(recipient)) {
            throw new IllegalArgumentException("complete recipient is required");
        }
    }

    private static String normalizeCandidate(String text) {
        if (text == null || text.isBlank()) return null;
        String candidate = text.strip()
                .replaceFirst("^(?:完整收款公司(?:名稱)?是|完整(?:公司)?名稱是|收款人是|公司是|是)"
                        + "[：:，,\\s]*", "")
                .replaceFirst("[。！!]$", "").strip();
        return candidate.length() <= 180 && COMPLETE_ORGANIZATION.matcher(candidate).matches()
                && !isMaskedRecipient(candidate) ? candidate : null;
    }

    private String retentionDisclosure(BankTransferDraft draft) {
        if (draftRetention != null) {
            return draftRetention.disclose(DraftType.BANK_TRANSFER, draft.getId());
        }
        return fallbackDisclosure(draft.getExpiresAt());
    }

    private static String fallbackDisclosure(Instant expiresAt) {
        return "這份草稿會保留到 %s 24:00，之後刪除。"
                .formatted(DraftRetentionService.expiryDay(expiresAt));
    }

    public record DraftView(Long id, String displayedRecipient, String purpose,
                            Integer amountTwd, LocalDate transferredAt,
                            String retentionNotice) {
        static DraftView from(BankTransferDraft draft, String retentionNotice) {
            return new DraftView(draft.getId(), draft.getDisplayedRecipient(), draft.getPurpose(),
                    draft.getAmountTwd(), draft.getTransferredAt(), retentionNotice);
        }
    }
}
