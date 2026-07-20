package com.aproject.aidriven.mymobilesecretary.schedule.decision.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 只有一個最終行程的條件場地草稿。 */
@Entity
@Table(name = "conditional_venue_draft")
public class ConditionalVenueDraft extends WorkspaceOwnedEntity {

    public enum Status { PENDING, RESOLVED, CANCELED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false)
    private Instant eventStartAt;
    @Column(nullable = false)
    private Instant eventEndAt;
    @Column(nullable = false, length = 200)
    private String primaryPlaceName;
    @Column(nullable = false, length = 200)
    private String fallbackPlaceName;
    @Column(nullable = false)
    private Instant decisionAt;
    @Column(nullable = false)
    private Long decisionTaskId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(length = 200)
    private String selectedPlaceName;
    private Long scheduleItemId;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ConditionalVenueDraft() {
    }

    public static ConditionalVenueDraft create(
            String title, Instant eventStartAt, Instant eventEndAt,
            String primaryPlaceName, String fallbackPlaceName,
            Instant decisionAt, Long decisionTaskId, Instant now) {
        if (!eventEndAt.isAfter(eventStartAt) || !decisionAt.isBefore(eventStartAt)) {
            throw new IllegalArgumentException("decision must precede a valid event interval");
        }
        if (primaryPlaceName.equalsIgnoreCase(fallbackPlaceName)) {
            throw new IllegalArgumentException("conditional places must be different");
        }
        ConditionalVenueDraft draft = new ConditionalVenueDraft();
        draft.title = title;
        draft.eventStartAt = eventStartAt;
        draft.eventEndAt = eventEndAt;
        draft.primaryPlaceName = primaryPlaceName;
        draft.fallbackPlaceName = fallbackPlaceName;
        draft.decisionAt = decisionAt;
        draft.decisionTaskId = decisionTaskId;
        draft.status = Status.PENDING;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void resolve(String selectedPlaceName, Long scheduleItemId, Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("venue draft is not pending");
        boolean supported = primaryPlaceName.equalsIgnoreCase(selectedPlaceName)
                || fallbackPlaceName.equalsIgnoreCase(selectedPlaceName);
        if (!supported) throw new IllegalArgumentException("selected place is not a draft option");
        this.selectedPlaceName = selectedPlaceName;
        this.scheduleItemId = scheduleItemId;
        this.status = Status.RESOLVED;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public Instant getEventStartAt() { return eventStartAt; }
    public Instant getEventEndAt() { return eventEndAt; }
    public String getPrimaryPlaceName() { return primaryPlaceName; }
    public String getFallbackPlaceName() { return fallbackPlaceName; }
    public Instant getDecisionAt() { return decisionAt; }
    public Long getDecisionTaskId() { return decisionTaskId; }
    public Status getStatus() { return status; }
    public String getSelectedPlaceName() { return selectedPlaceName; }
    public Long getScheduleItemId() { return scheduleItemId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
