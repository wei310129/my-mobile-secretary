package com.aproject.aidriven.mymobilesecretary.shared.time;

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

/** Actor-scoped preference for human-readable conversational time labels. */
@Entity
@Table(name = "time_display_preference")
public class TimeDisplayPreference extends WorkspaceOwnedEntity {

    public enum DisplayFormat {
        TWELVE_HOUR,
        TWENTY_FOUR_HOUR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DisplayFormat displayFormat;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TimeDisplayPreference() {
    }

    public static TimeDisplayPreference create(DisplayFormat displayFormat, Instant now) {
        TimeDisplayPreference value = new TimeDisplayPreference();
        value.changeTo(displayFormat, now);
        return value;
    }

    public void changeTo(DisplayFormat displayFormat, Instant now) {
        if (displayFormat == null || now == null) {
            throw new IllegalArgumentException("time display preference is required");
        }
        this.displayFormat = displayFormat;
        this.updatedAt = now;
    }

    public DisplayFormat getDisplayFormat() {
        return displayFormat;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
