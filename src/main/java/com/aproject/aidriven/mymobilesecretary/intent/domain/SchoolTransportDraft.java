package com.aproject.aidriven.mymobilesecretary.intent.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 逐輪補齊孩子固定上課與本人接送資訊的私人草稿。 */
@Entity
@Table(name = "school_transport_draft")
public class SchoolTransportDraft extends WorkspaceOwnedEntity {

    public enum Status { PENDING, COMPLETED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 8000)
    private String payload;

    @Column(name = "child_name", length = 120) private String childName;
    @Column(name = "course_name", length = 200) private String courseName;
    @Enumerated(EnumType.STRING) @Column(length = 12) private DayOfWeek weekday;
    @Column(name = "course_start") private LocalTime courseStart;
    @Column(name = "course_end") private LocalTime courseEnd;
    @Column(name = "recurrence_until") private LocalDate recurrenceUntil;
    @Column(name = "drop_person", length = 120) private String dropPerson;
    @Column(name = "drop_origin", length = 200) private String dropOrigin;
    @Column(name = "drop_start") private LocalTime dropStart;
    @Column(name = "pickup_person", length = 120) private String pickupPerson;
    @Column(name = "pickup_location", length = 200) private String pickupLocation;
    @Column(name = "pickup_end") private LocalTime pickupEnd;
    @Column(name = "source_schedule_id") private Long sourceScheduleId;
    @Column(name = "source_schedule_title", length = 200) private String sourceScheduleTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SchoolTransportDraft() {
    }

    public static SchoolTransportDraft create(String title, String payload,
                                               Instant expiresAt, Instant now) {
        SchoolTransportDraft draft = new SchoolTransportDraft();
        draft.title = title;
        draft.payload = payload;
        draft.status = Status.PENDING;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public static SchoolTransportDraft createTyped(
            String title, String childName, String courseName, DayOfWeek weekday,
            LocalTime courseStart, LocalTime courseEnd, LocalDate recurrenceUntil,
            String dropPerson, String dropOrigin, LocalTime dropStart,
            String pickupPerson, String pickupLocation, LocalTime pickupEnd,
            Long sourceScheduleId, String sourceScheduleTitle,
            Instant expiresAt, Instant now) {
        SchoolTransportDraft draft = new SchoolTransportDraft();
        draft.title = title;
        draft.replaceTyped(childName, courseName, weekday, courseStart, courseEnd,
                recurrenceUntil, dropPerson, dropOrigin, dropStart, pickupPerson,
                pickupLocation, pickupEnd, sourceScheduleId, sourceScheduleTitle, now, false);
        draft.status = Status.PENDING;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        return draft;
    }

    public void replace(String title, String payload, Instant now) {
        requirePending(now);
        this.title = title;
        this.payload = payload;
        this.updatedAt = now;
    }

    public void replaceTyped(
            String title, String childName, String courseName, DayOfWeek weekday,
            LocalTime courseStart, LocalTime courseEnd, LocalDate recurrenceUntil,
            String dropPerson, String dropOrigin, LocalTime dropStart,
            String pickupPerson, String pickupLocation, LocalTime pickupEnd,
            Long sourceScheduleId, String sourceScheduleTitle, Instant now) {
        requirePending(now);
        this.title = title;
        replaceTyped(childName, courseName, weekday, courseStart, courseEnd,
                recurrenceUntil, dropPerson, dropOrigin, dropStart, pickupPerson,
                pickupLocation, pickupEnd, sourceScheduleId, sourceScheduleTitle, now, true);
    }

    private void replaceTyped(
            String childName, String courseName, DayOfWeek weekday,
            LocalTime courseStart, LocalTime courseEnd, LocalDate recurrenceUntil,
            String dropPerson, String dropOrigin, LocalTime dropStart,
            String pickupPerson, String pickupLocation, LocalTime pickupEnd,
            Long sourceScheduleId, String sourceScheduleTitle, Instant now, boolean clearLegacy) {
        if (childName == null || childName.isBlank() || courseName == null || courseName.isBlank()) {
            throw new IllegalArgumentException("school transport child and course are required");
        }
        if (courseStart != null && courseEnd != null && !courseEnd.isAfter(courseStart)) {
            throw new IllegalArgumentException("school transport course interval is invalid");
        }
        if (clearLegacy) this.payload = null;
        this.childName = childName;
        this.courseName = courseName;
        this.weekday = weekday;
        this.courseStart = courseStart;
        this.courseEnd = courseEnd;
        this.recurrenceUntil = recurrenceUntil;
        this.dropPerson = dropPerson;
        this.dropOrigin = dropOrigin;
        this.dropStart = dropStart;
        this.pickupPerson = pickupPerson;
        this.pickupLocation = pickupLocation;
        this.pickupEnd = pickupEnd;
        this.sourceScheduleId = sourceScheduleId;
        this.sourceScheduleTitle = sourceScheduleTitle;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        requirePending(now);
        status = Status.COMPLETED;
        updatedAt = now;
    }

    private void requirePending(Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("school transport draft is not pending");
        if (!expiresAt.isAfter(now)) throw new IllegalStateException("school transport draft expired");
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getPayload() { return payload; }
    public String getChildName() { return childName; }
    public String getCourseName() { return courseName; }
    public DayOfWeek getWeekday() { return weekday; }
    public LocalTime getCourseStart() { return courseStart; }
    public LocalTime getCourseEnd() { return courseEnd; }
    public LocalDate getRecurrenceUntil() { return recurrenceUntil; }
    public String getDropPerson() { return dropPerson; }
    public String getDropOrigin() { return dropOrigin; }
    public LocalTime getDropStart() { return dropStart; }
    public String getPickupPerson() { return pickupPerson; }
    public String getPickupLocation() { return pickupLocation; }
    public LocalTime getPickupEnd() { return pickupEnd; }
    public Long getSourceScheduleId() { return sourceScheduleId; }
    public String getSourceScheduleTitle() { return sourceScheduleTitle; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
}
