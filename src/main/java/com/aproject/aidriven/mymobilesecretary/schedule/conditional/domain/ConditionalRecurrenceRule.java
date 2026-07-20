package com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Duration;
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

/**
 * A weekly intent whose occurrence date depends on official facts.
 *
 * <p>This is deliberately not a {@code ScheduleItem}: a condition must be resolved before a
 * concrete one-off schedule may be proposed.
 */
@Entity
@Table(name = "conditional_recurrence_rule")
public class ConditionalRecurrenceRule extends WorkspaceOwnedEntity {

    public enum HolidayPolicy {
        NONE,
        PREVIOUS_BUSINESS_DAY,
        SKIP
    }

    public enum ClosurePolicy {
        NONE,
        NEXT_BUSINESS_DAY
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        PAUSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private Instant anchorStartAt;

    @Column(nullable = false)
    private int durationMinutes;

    private LocalDate recurrenceUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private HolidayPolicy holidayPolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ClosurePolicy closurePolicy;

    @Column(length = 80)
    private String closureJurisdiction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConditionalRecurrenceRule() {
    }

    public static ConditionalRecurrenceRule draft(
            String title,
            Instant anchorStartAt,
            Instant anchorEndAt,
            LocalDate recurrenceUntil,
            HolidayPolicy holidayPolicy,
            ClosurePolicy closurePolicy,
            String closureJurisdiction,
            Instant now) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("conditional recurrence title is invalid");
        }
        if (anchorStartAt == null || anchorEndAt == null || !anchorEndAt.isAfter(anchorStartAt)) {
            throw new IllegalArgumentException("conditional recurrence time range is invalid");
        }
        long minutes = Duration.between(anchorStartAt, anchorEndAt).toMinutes();
        if (minutes < 1 || minutes > 24 * 60) {
            throw new IllegalArgumentException("conditional recurrence duration is invalid");
        }
        if (holidayPolicy == null || closurePolicy == null) {
            throw new IllegalArgumentException("conditional recurrence policy is required");
        }
        if (closurePolicy != ClosurePolicy.NONE
                && (closureJurisdiction == null || closureJurisdiction.isBlank())) {
            throw new IllegalArgumentException("closure jurisdiction is required");
        }
        ConditionalRecurrenceRule rule = new ConditionalRecurrenceRule();
        rule.title = title.strip();
        rule.anchorStartAt = anchorStartAt;
        rule.durationMinutes = Math.toIntExact(minutes);
        rule.recurrenceUntil = recurrenceUntil;
        rule.holidayPolicy = holidayPolicy;
        rule.closurePolicy = closurePolicy;
        rule.closureJurisdiction = closureJurisdiction == null ? null : closureJurisdiction.strip();
        rule.status = Status.DRAFT;
        rule.createdAt = now;
        rule.updatedAt = now;
        return rule;
    }

    public void activate(Instant now) {
        if (status != Status.DRAFT && status != Status.PAUSED) {
            throw new IllegalStateException("conditional recurrence rule is already active");
        }
        status = Status.ACTIVE;
        updatedAt = now;
    }

    public void pause(Instant now) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("only an active conditional recurrence rule can pause");
        }
        status = Status.PAUSED;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getAnchorStartAt() {
        return anchorStartAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public LocalDate getRecurrenceUntil() {
        return recurrenceUntil;
    }

    public HolidayPolicy getHolidayPolicy() {
        return holidayPolicy;
    }

    public ClosurePolicy getClosurePolicy() {
        return closurePolicy;
    }

    public String getClosureJurisdiction() {
        return closureJurisdiction;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
