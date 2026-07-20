package com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Auditable outcome for one base occurrence of a conditional recurrence rule. */
@Entity
@Table(name = "conditional_recurrence_resolution", uniqueConstraints = @UniqueConstraint(
        name = "uq_conditional_resolution_rule_date", columnNames = {"rule_id", "base_date"}))
public class ConditionalRecurrenceResolution extends WorkspaceOwnedEntity {

    public enum Status {
        READY,
        SKIPPED,
        WAITING_OFFICIAL_CONFIRMATION,
        OUTSIDE_RULE_RANGE,
        RULE_NOT_ACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    private Instant resolvedStartAt;

    private Instant resolvedEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Status status;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(length = 2000)
    private String officialSourceSnapshot;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConditionalRecurrenceResolution() {
    }

    public static ConditionalRecurrenceResolution create(
            Long ruleId, LocalDate baseDate, Instant now) {
        ConditionalRecurrenceResolution value = new ConditionalRecurrenceResolution();
        value.ruleId = ruleId;
        value.baseDate = baseDate;
        value.status = Status.WAITING_OFFICIAL_CONFIRMATION;
        value.reason = "尚未解析";
        value.updatedAt = now;
        return value;
    }

    public void record(
            Status status,
            Instant resolvedStartAt,
            Instant resolvedEndAt,
            String reason,
            String officialSourceSnapshot,
            Instant now) {
        if (status == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("conditional resolution is invalid");
        }
        if (status == Status.READY
                && (resolvedStartAt == null
                || resolvedEndAt == null
                || !resolvedEndAt.isAfter(resolvedStartAt))) {
            throw new IllegalArgumentException("ready resolution requires a time range");
        }
        if (status != Status.READY && (resolvedStartAt != null || resolvedEndAt != null)) {
            throw new IllegalArgumentException("unresolved occurrence cannot have a time range");
        }
        this.status = status;
        this.resolvedStartAt = resolvedStartAt;
        this.resolvedEndAt = resolvedEndAt;
        this.reason = reason;
        this.officialSourceSnapshot = officialSourceSnapshot;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public LocalDate getBaseDate() {
        return baseDate;
    }

    public Instant getResolvedStartAt() {
        return resolvedStartAt;
    }

    public Instant getResolvedEndAt() {
        return resolvedEndAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getOfficialSourceSnapshot() {
        return officialSourceSnapshot;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
