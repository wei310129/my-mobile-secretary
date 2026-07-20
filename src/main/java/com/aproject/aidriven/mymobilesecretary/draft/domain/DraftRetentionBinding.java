package com.aproject.aidriven.mymobilesecretary.draft.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Effective timing plus independent default/custom inheritance flags for one draft. */
@Entity
public class DraftRetentionBinding extends WorkspaceOwnedEntity {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration MINIMUM_NOTICE = Duration.ofMinutes(5);

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private DraftType draftType;
    @Column(nullable = false)
    private Long draftId;
    @Column(nullable = false, length = 240)
    private String title;
    @Column(nullable = false)
    private Instant lastEditedAt;
    @Column(nullable = false)
    private boolean usesDefaultRetention;
    private Integer customRetentionDays;
    @Column(nullable = false)
    private boolean usesDefaultReminder;
    private Integer customReminderDaysBefore;
    private LocalTime customReminderTime;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private Instant remindAt;
    private Instant notifiedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected DraftRetentionBinding() {}

    public static DraftRetentionBinding create(DraftType type, Long draftId, String title,
                                               DraftRetentionPreference defaults, Instant now) {
        if (type == null || draftId == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("draft retention identity is required");
        }
        DraftRetentionBinding binding = new DraftRetentionBinding();
        binding.draftType = type;
        binding.draftId = draftId;
        binding.title = clean(title);
        binding.lastEditedAt = now;
        binding.usesDefaultRetention = true;
        binding.usesDefaultReminder = true;
        binding.createdAt = now;
        binding.recalculate(defaults, now);
        return binding;
    }

    public void touch(DraftRetentionPreference defaults, Instant now) {
        lastEditedAt = now;
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void customizeRetention(int days, DraftRetentionPreference defaults, Instant now) {
        DraftRetentionPreference.requireRetentionDays(days);
        usesDefaultRetention = false;
        customRetentionDays = days;
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void useDefaultRetention(DraftRetentionPreference defaults, Instant now) {
        usesDefaultRetention = true;
        customRetentionDays = null;
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void customizeReminder(int daysBefore, LocalTime time,
                                  DraftRetentionPreference defaults, Instant now) {
        DraftRetentionPreference.requireReminder(daysBefore, time, effectiveRetentionDays(defaults));
        usesDefaultReminder = false;
        customReminderDaysBefore = daysBefore;
        customReminderTime = time;
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void customize(Integer retentionDays, Integer reminderDaysBefore,
                          LocalTime reminderTime, DraftRetentionPreference defaults, Instant now) {
        if (retentionDays != null) {
            DraftRetentionPreference.requireRetentionDays(retentionDays);
            usesDefaultRetention = false;
            customRetentionDays = retentionDays;
        }
        if (reminderDaysBefore != null || reminderTime != null) {
            int daysBefore = reminderDaysBefore == null
                    ? effectiveReminderDaysBefore(defaults) : reminderDaysBefore;
            LocalTime time = reminderTime == null
                    ? effectiveReminderTime(defaults) : reminderTime;
            DraftRetentionPreference.requireReminder(daysBefore, time,
                    effectiveRetentionDays(defaults));
            usesDefaultReminder = false;
            customReminderDaysBefore = daysBefore;
            customReminderTime = time;
        }
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void useDefaultReminder(DraftRetentionPreference defaults, Instant now) {
        usesDefaultReminder = true;
        customReminderDaysBefore = null;
        customReminderTime = null;
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void useDefaults(boolean retention, boolean reminder,
                            DraftRetentionPreference defaults, Instant now) {
        if (retention) {
            usesDefaultRetention = true;
            customRetentionDays = null;
        }
        if (reminder) {
            usesDefaultReminder = true;
            customReminderDaysBefore = null;
            customReminderTime = null;
        }
        notifiedAt = null;
        recalculate(defaults, now);
    }

    public void refreshDefaults(DraftRetentionPreference defaults, Instant now) {
        if (usesDefaultRetention || usesDefaultReminder) {
            notifiedAt = null;
            recalculate(defaults, now);
        }
    }

    public void markNotified(Instant now) {
        notifiedAt = now;
        updatedAt = now;
    }

    private void recalculate(DraftRetentionPreference defaults, Instant receivedAt) {
        int retentionDays = effectiveRetentionDays(defaults);
        int reminderDays = usesDefaultReminder
                ? defaults.getDefaultReminderDaysBefore() : customReminderDaysBefore;
        LocalTime reminderTime = usesDefaultReminder
                ? defaults.getDefaultReminderTime() : customReminderTime;
        DraftRetentionPreference.requireReminder(reminderDays, reminderTime, retentionDays);
        LocalDate editedOn = lastEditedAt.atZone(TAIPEI).toLocalDate();
        LocalDate expiryDay = editedOn.plusDays(retentionDays);
        expiresAt = expiryDay.plusDays(1).atStartOfDay(TAIPEI).toInstant();
        remindAt = expiryDay.minusDays(reminderDays).atTime(reminderTime)
                .atZone(TAIPEI).toInstant();
        if (remindAt.isBefore(receivedAt.plus(MINIMUM_NOTICE))) {
            throw new IllegalArgumentException(
                    "draft reminder must be at least five minutes in the future");
        }
        if (!remindAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("draft reminder must be before deletion");
        }
        updatedAt = receivedAt;
    }

    private int effectiveRetentionDays(DraftRetentionPreference defaults) {
        return usesDefaultRetention ? defaults.getDefaultRetentionDays() : customRetentionDays;
    }

    private int effectiveReminderDaysBefore(DraftRetentionPreference defaults) {
        return usesDefaultReminder
                ? defaults.getDefaultReminderDaysBefore() : customReminderDaysBefore;
    }

    private LocalTime effectiveReminderTime(DraftRetentionPreference defaults) {
        return usesDefaultReminder ? defaults.getDefaultReminderTime() : customReminderTime;
    }

    private static String clean(String value) {
        String result = value.strip().replace('\n', ' ').replace('\r', ' ');
        return result.length() <= 240 ? result : result.substring(0, 240);
    }

    public DraftType getDraftType() { return draftType; }
    public Long getDraftId() { return draftId; }
    public String getTitle() { return title; }
    public boolean isUsesDefaultRetention() { return usesDefaultRetention; }
    public boolean isUsesDefaultReminder() { return usesDefaultReminder; }
    public int getEffectiveRetentionDays(DraftRetentionPreference defaults) {
        return effectiveRetentionDays(defaults);
    }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRemindAt() { return remindAt; }
    public Instant getNotifiedAt() { return notifiedAt; }

    public enum DraftType { BANK_TRANSFER, PRODUCT_OBSERVATION }
}
