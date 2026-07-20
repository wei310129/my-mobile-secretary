package com.aproject.aidriven.mymobilesecretary.payment.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Pending bank-transfer consumption whose masked recipient must be supplied by the user. */
@Entity
public class BankTransferDraft extends WorkspaceOwnedEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 180)
    private String displayedRecipient;
    @Column(length = 180)
    private String confirmedRecipient;
    @Column(nullable = false, length = 120)
    private String purpose;
    private Integer amountTwd;
    private LocalDate transferredAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Status status;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected BankTransferDraft() {
    }

    public static BankTransferDraft pending(String displayedRecipient, String purpose,
                                            Integer amountTwd, LocalDate transferredAt,
                                            Instant expiresAt, Instant now) {
        BankTransferDraft draft = new BankTransferDraft();
        draft.displayedRecipient = optional(displayedRecipient, 180);
        String cleanPurpose = optional(purpose, 120);
        draft.purpose = cleanPurpose == null ? "轉帳" : cleanPurpose;
        draft.amountTwd = amountTwd != null && amountTwd > 0 ? amountTwd : null;
        draft.transferredAt = transferredAt;
        draft.status = Status.PENDING_RECIPIENT;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void confirmRecipient(String recipient, Instant now) {
        if (status != Status.PENDING_RECIPIENT) {
            throw new IllegalStateException("bank transfer draft is not pending");
        }
        confirmedRecipient = optional(recipient, 180);
        if (confirmedRecipient == null) throw new IllegalArgumentException("recipient is required");
        updatedAt = now;
    }

    public void extendRetention(Instant now, Instant newExpiresAt) {
        if (status != Status.PENDING_RECIPIENT || newExpiresAt == null
                || !newExpiresAt.isAfter(now)) {
            throw new IllegalStateException("only a pending bank transfer draft can be extended");
        }
        updatedAt = now;
        expiresAt = newExpiresAt;
    }

    public void complete(Instant now) {
        if (confirmedRecipient == null || amountTwd == null || transferredAt == null) {
            throw new IllegalStateException("bank transfer draft is incomplete");
        }
        status = Status.COMPLETED;
        updatedAt = now;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    public Long getId() { return id; }
    public String getDisplayedRecipient() { return displayedRecipient; }
    public String getConfirmedRecipient() { return confirmedRecipient; }
    public String getPurpose() { return purpose; }
    public Integer getAmountTwd() { return amountTwd; }
    public LocalDate getTransferredAt() { return transferredAt; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }

    public enum Status { PENDING_RECIPIENT, COMPLETED, EXPIRED }
}
