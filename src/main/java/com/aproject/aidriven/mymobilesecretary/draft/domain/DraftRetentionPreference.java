package com.aproject.aidriven.mymobilesecretary.draft.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Actor-private defaults inherited independently by short-lived draft expiry and reminders. */
@Entity
public class DraftRetentionPreference extends WorkspaceOwnedEntity {
    public static final int MAX_RETENTION_DAYS = 30;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private int defaultRetentionDays;
    @Column(nullable = false)
    private int defaultReminderDaysBefore;
    @Column(nullable = false)
    private LocalTime defaultReminderTime;
    @Column(nullable = false)
    private boolean settingsConfirmed;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected DraftRetentionPreference() {}

    public static DraftRetentionPreference suggested(Instant now) {
        DraftRetentionPreference preference = new DraftRetentionPreference();
        preference.defaultRetentionDays = 7;
        preference.defaultReminderDaysBefore = 0;
        preference.defaultReminderTime = LocalTime.of(20, 0);
        preference.settingsConfirmed = false;
        preference.createdAt = now;
        preference.updatedAt = now;
        return preference;
    }

    public void changeRetentionDays(int days, Instant now) {
        requireRetentionDays(days);
        if (defaultReminderDaysBefore > days) {
            throw new IllegalArgumentException("default reminder cannot be before draft creation");
        }
        defaultRetentionDays = days;
        settingsConfirmed = true;
        updatedAt = now;
    }

    public void changeReminder(int daysBefore, LocalTime time, Instant now) {
        requireReminder(daysBefore, time, defaultRetentionDays);
        defaultReminderDaysBefore = daysBefore;
        defaultReminderTime = time;
        settingsConfirmed = true;
        updatedAt = now;
    }

    public void confirm(Instant now) {
        settingsConfirmed = true;
        updatedAt = now;
    }

    public static void requireRetentionDays(int days) {
        if (days < 1 || days > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("draft retention must be between 1 and 30 days");
        }
    }

    public static void requireReminder(int daysBefore, LocalTime time, int retentionDays) {
        if (daysBefore < 0 || daysBefore > retentionDays || time == null
                || time.isAfter(LocalTime.of(23, 0))) {
            throw new IllegalArgumentException("draft reminder is outside the allowed range");
        }
    }

    public int getDefaultRetentionDays() { return defaultRetentionDays; }
    public int getDefaultReminderDaysBefore() { return defaultReminderDaysBefore; }
    public LocalTime getDefaultReminderTime() { return defaultReminderTime; }
    public boolean isSettingsConfirmed() { return settingsConfirmed; }
}
