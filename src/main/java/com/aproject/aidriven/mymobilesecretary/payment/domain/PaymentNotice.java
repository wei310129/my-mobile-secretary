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

/** A not-yet-paid notice. It is never evidence that a payment occurred. */
@Entity
public class PaymentNotice extends WorkspaceOwnedEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 180)
    private String issuer;
    private Integer amountTwd;
    @Column(nullable = false)
    private LocalDate dueDate;
    private Integer reminderLeadDays;
    private Long flexiblePlanId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Status status;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentNotice() {
    }

    public static PaymentNotice pending(String title, String issuer, Integer amountTwd,
                                        LocalDate dueDate, Instant now) {
        if (title == null || title.isBlank() || dueDate == null) {
            throw new IllegalArgumentException("payment title and due date are required");
        }
        PaymentNotice notice = new PaymentNotice();
        notice.title = clean(title, 200);
        notice.issuer = clean(issuer, 180);
        notice.amountTwd = amountTwd != null && amountTwd > 0 ? amountTwd : null;
        notice.dueDate = dueDate;
        notice.status = Status.PENDING_REMINDER;
        notice.createdAt = now;
        notice.updatedAt = now;
        return notice;
    }

    public void schedule(int leadDays, Long planId, Instant now) {
        if (status != Status.PENDING_REMINDER || leadDays < 0 || leadDays > 365
                || planId == null || planId <= 0) {
            throw new IllegalArgumentException("payment reminder plan is invalid");
        }
        reminderLeadDays = leadDays;
        flexiblePlanId = planId;
        status = Status.SCHEDULED;
        updatedAt = now;
    }

    public void complete(Instant now) {
        if (status == Status.SCHEDULED) {
            status = Status.COMPLETED;
            updatedAt = now;
        }
    }

    public void cancel(Instant now) {
        if (status == Status.SCHEDULED) {
            status = Status.CANCELED;
            updatedAt = now;
        }
    }

    private static String clean(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip().replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getIssuer() { return issuer; }
    public Integer getAmountTwd() { return amountTwd; }
    public LocalDate getDueDate() { return dueDate; }
    public Integer getReminderLeadDays() { return reminderLeadDays; }
    public Long getFlexiblePlanId() { return flexiblePlanId; }
    public Status getStatus() { return status; }

    public enum Status { PENDING_REMINDER, SCHEDULED, COMPLETED, CANCELED }
}
