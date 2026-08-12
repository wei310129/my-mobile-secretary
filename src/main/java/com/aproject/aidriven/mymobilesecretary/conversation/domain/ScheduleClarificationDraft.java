package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Typed, scope-bound intake for the three schedule clarification capabilities. */
@Entity
@Table(name = "schedule_clarification_draft")
public class ScheduleClarificationDraft extends WorkspaceOwnedEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private ScheduleClarificationCapability capability;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;
    @Column(length = 200) private String title;
    @Enumerated(EnumType.STRING) @Column(length = 12) private DayOfWeek weekday;
    @Column(name = "ordinal_value") private Integer ordinalValue;
    @Column(name = "month_offset") private Integer monthOffset;
    @Column(name = "start_time") private LocalTime startTime;
    @Column(name = "time_period_explicit", nullable = false) private boolean timePeriodExplicit;
    @Column(name = "duration_minutes") private Integer durationMinutes;
    @Column(name = "until_date") private LocalDate untilDate;
    @Column(name = "recurrence_explicit", nullable = false) private boolean recurrenceExplicit;
    @Column(name = "holiday_policy", length = 40) private String holidayPolicy;
    @Column(name = "closure_policy", length = 40) private String closurePolicy;
    @Column(length = 40) private String jurisdiction;
    @Column(name = "event_at") private Instant eventAt;
    @Column(name = "primary_place", length = 200) private String primaryPlace;
    @Column(name = "fallback_place", length = 200) private String fallbackPlace;
    @Column(name = "decision_at") private Instant decisionAt;
    @Column(name = "decision_period_explicit", nullable = false) private boolean decisionPeriodExplicit;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ScheduleClarificationDraftStatus status;
    @Column(nullable = false) private long revision;
    @Column(nullable = false) private Instant expiresAt;
    @Version private Long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected ScheduleClarificationDraft() {}

    public static ScheduleClarificationDraft create(
            ConversationScopeKey scope, WorkspaceChannel channel,
            ScheduleClarificationCapability capability, Instant expiresAt, Instant now) {
        ScheduleClarificationDraft draft = new ScheduleClarificationDraft();
        draft.id = UUID.randomUUID();
        draft.capability = capability;
        draft.channel = channel;
        draft.conversationScopeDigest = scope.digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.status = ScheduleClarificationDraftStatus.PENDING;
        draft.revision = 1;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void mergeRecurrence(String title, DayOfWeek weekday, LocalTime startTime,
                                boolean timePeriodExplicit, Integer durationMinutes,
                                LocalDate untilDate, boolean recurrenceExplicit,
                                String holidayPolicy, String closurePolicy,
                                String jurisdiction, Instant now) {
        require(ScheduleClarificationCapability.CONDITIONAL_RECURRENCE, now);
        this.title = prefer(title, this.title);
        this.weekday = prefer(weekday, this.weekday);
        this.startTime = prefer(startTime, this.startTime);
        this.timePeriodExplicit |= timePeriodExplicit;
        this.durationMinutes = prefer(durationMinutes, this.durationMinutes);
        this.untilDate = prefer(untilDate, this.untilDate);
        this.recurrenceExplicit |= recurrenceExplicit;
        this.holidayPolicy = prefer(holidayPolicy, this.holidayPolicy);
        this.closurePolicy = prefer(closurePolicy, this.closurePolicy);
        this.jurisdiction = prefer(jurisdiction, this.jurisdiction);
        touch(now);
    }

    public void mergeMonthly(Integer ordinal, Integer monthOffset, DayOfWeek weekday, LocalTime startTime,
                             boolean timePeriodExplicit, Integer durationMinutes,
                             String title, Instant now) {
        require(ScheduleClarificationCapability.MONTHLY_ORDINAL, now);
        this.ordinalValue = prefer(ordinal, this.ordinalValue);
        this.monthOffset = prefer(monthOffset, this.monthOffset);
        this.weekday = prefer(weekday, this.weekday);
        this.startTime = prefer(startTime, this.startTime);
        this.timePeriodExplicit |= timePeriodExplicit;
        this.durationMinutes = prefer(durationMinutes, this.durationMinutes);
        this.title = prefer(title, this.title);
        touch(now);
    }

    public void mergeVenue(Instant eventAt, Integer durationMinutes, String title,
                           String primaryPlace, String fallbackPlace, Instant decisionAt,
                           boolean decisionPeriodExplicit, Instant now) {
        require(ScheduleClarificationCapability.CONDITIONAL_VENUE, now);
        this.eventAt = prefer(eventAt, this.eventAt);
        this.durationMinutes = prefer(durationMinutes, this.durationMinutes);
        this.title = prefer(title, this.title);
        this.primaryPlace = prefer(primaryPlace, this.primaryPlace);
        this.fallbackPlace = prefer(fallbackPlace, this.fallbackPlace);
        this.decisionAt = prefer(decisionAt, this.decisionAt);
        this.decisionPeriodExplicit |= decisionPeriodExplicit;
        touch(now);
    }

    public void complete(Instant now) { require(capability, now); status = ScheduleClarificationDraftStatus.COMPLETED; touch(now); }
    public void cancel(Instant now) { require(capability, now); status = ScheduleClarificationDraftStatus.CANCELED; touch(now); }
    public boolean expireIfDue(Instant now) { if (status == ScheduleClarificationDraftStatus.PENDING && !expiresAt.isAfter(now)) { status = ScheduleClarificationDraftStatus.EXPIRED; updatedAt = now; return true; } return false; }
    private void require(ScheduleClarificationCapability expected, Instant now) { if (capability != expected || status != ScheduleClarificationDraftStatus.PENDING || !expiresAt.isAfter(now)) throw new IllegalStateException("schedule clarification draft is unavailable"); }
    private void touch(Instant now) { revision++; updatedAt = now; }
    private static <T> T prefer(T candidate, T existing) { return candidate == null ? existing : candidate; }

    public UUID getId() { return id; } public ScheduleClarificationCapability getCapability() { return capability; }
    public String getTitle() { return title; } public DayOfWeek getWeekday() { return weekday; }
    public Integer getOrdinalValue() { return ordinalValue; } public LocalTime getStartTime() { return startTime; }
    public Integer getMonthOffset() { return monthOffset; }
    public boolean isTimePeriodExplicit() { return timePeriodExplicit; } public Integer getDurationMinutes() { return durationMinutes; }
    public LocalDate getUntilDate() { return untilDate; } public boolean isRecurrenceExplicit() { return recurrenceExplicit; }
    public String getHolidayPolicy() { return holidayPolicy; } public String getClosurePolicy() { return closurePolicy; }
    public String getJurisdiction() { return jurisdiction; } public Instant getEventAt() { return eventAt; }
    public String getPrimaryPlace() { return primaryPlace; } public String getFallbackPlace() { return fallbackPlace; }
    public Instant getDecisionAt() { return decisionAt; } public boolean isDecisionPeriodExplicit() { return decisionPeriodExplicit; }
    public long getRevision() { return revision; } public ScheduleClarificationDraftStatus getStatus() { return status; }
}
