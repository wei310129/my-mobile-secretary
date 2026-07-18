package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 個人生活硬需求時間窗；只供規劃提示，不會偽裝成行程。 */
@Entity
@Table(name = "lifestyle_window", uniqueConstraints = @UniqueConstraint(
        name = "uq_lifestyle_window_actor_day_kind",
        columnNames = {"workspace_id", "created_by_user_id", "day_type", "kind"}))
public class LifestyleWindow extends WorkspaceOwnedEntity {

    public enum DayType { WEEKDAY, HOLIDAY }
    public enum Kind { BREAKFAST, LUNCH, DINNER, SLEEP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private Instant updatedAt;

    protected LifestyleWindow() {
    }

    public static LifestyleWindow create(DayType dayType, Kind kind,
                                         LocalTime startTime, LocalTime endTime, Instant now) {
        LifestyleWindow value = new LifestyleWindow();
        value.update(dayType, kind, startTime, endTime, now);
        return value;
    }

    public void update(DayType dayType, Kind kind,
                       LocalTime startTime, LocalTime endTime, Instant now) {
        if (dayType == null || kind == null || startTime == null || endTime == null
                || startTime.equals(endTime)) {
            throw new IllegalArgumentException("lifestyle window is invalid");
        }
        this.dayType = dayType;
        this.kind = kind;
        this.startTime = startTime;
        this.endTime = endTime;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public DayType getDayType() { return dayType; }
    public Kind getKind() { return kind; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public Instant getUpdatedAt() { return updatedAt; }
}
