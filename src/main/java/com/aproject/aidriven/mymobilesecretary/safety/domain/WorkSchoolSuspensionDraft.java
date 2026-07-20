package com.aproject.aidriven.mymobilesecretary.safety.domain;

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

/** User-supplied suspension notice kept separate from official verification state. */
@Entity
public class WorkSchoolSuspensionDraft extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private LocalDate noticeDate;
    @Column(nullable = false, length = 2000)
    private String extractedSummary;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Status status;
    @Column(nullable = false)
    private Instant verifyAfter;
    @Column(length = 2000)
    private String officialSummary;
    @Column(length = 500)
    private String officialSourceUrl;
    private Instant verifiedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected WorkSchoolSuspensionDraft() {}

    public static WorkSchoolSuspensionDraft pending(LocalDate date, String summary,
                                                     Instant verifyAfter, Instant now) {
        if (date == null) throw new IllegalArgumentException("notice date is required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("notice summary is required");
        WorkSchoolSuspensionDraft draft = new WorkSchoolSuspensionDraft();
        draft.noticeDate = date;
        draft.extractedSummary = truncate(summary, 2000);
        draft.status = Status.PENDING_CONFIRMATION;
        draft.verifyAfter = java.util.Objects.requireNonNull(verifyAfter);
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void decline(Instant now) {
        requirePending();
        status = Status.DECLINED;
        updatedAt = now;
    }

    public void verified(String summary, String sourceUrl, boolean officialMatch, Instant now) {
        requirePending();
        status = officialMatch ? Status.OFFICIAL_CONFIRMED : Status.OFFICIAL_NOT_CONFIRMED;
        officialSummary = truncate(summary, 2000);
        officialSourceUrl = truncate(sourceUrl, 500);
        verifiedAt = now;
        updatedAt = now;
    }

    public void verificationFailed(String summary, String sourceUrl, Instant now) {
        requirePending();
        status = Status.VERIFICATION_FAILED;
        officialSummary = truncate(summary, 2000);
        officialSourceUrl = truncate(sourceUrl, 500);
        verifiedAt = now;
        updatedAt = now;
    }

    private void requirePending() {
        if (status != Status.PENDING_CONFIRMATION) {
            throw new IllegalStateException("suspension draft is no longer pending");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    public Long getId() { return id; }
    public LocalDate getNoticeDate() { return noticeDate; }
    public String getExtractedSummary() { return extractedSummary; }
    public Status getStatus() { return status; }
    public Instant getVerifyAfter() { return verifyAfter; }
    public String getOfficialSummary() { return officialSummary; }
    public String getOfficialSourceUrl() { return officialSourceUrl; }

    public enum Status {
        PENDING_CONFIRMATION, DECLINED, OFFICIAL_CONFIRMED,
        OFFICIAL_NOT_CONFIRMED, VERIFICATION_FAILED
    }
}
