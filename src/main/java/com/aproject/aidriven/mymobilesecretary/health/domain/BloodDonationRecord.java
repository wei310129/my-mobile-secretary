package com.aproject.aidriven.mymobilesecretary.health.domain;

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

/** Actor-private donation fact. Eligibility is stored only when explicitly supplied. */
@Entity
public class BloodDonationRecord extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private LocalDate donationDate;
    @Column(length = 180) private String donationLocation;
    private LocalDate nextEligibleDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SourceType sourceType;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected BloodDonationRecord() {}

    public static BloodDonationRecord create(LocalDate donationDate, String location,
                                              LocalDate nextEligibleDate, SourceType source,
                                              Instant now) {
        BloodDonationRecord record = new BloodDonationRecord();
        record.donationDate = java.util.Objects.requireNonNull(donationDate);
        record.sourceType = java.util.Objects.requireNonNull(source);
        record.createdAt = java.util.Objects.requireNonNull(now);
        record.update(location, nextEligibleDate, source, now);
        return record;
    }

    public void update(String location, LocalDate nextEligibleDate, SourceType source, Instant now) {
        if (location != null && !location.isBlank()) {
            String clean = location.strip().replace('\n', ' ').replace('\r', ' ');
            donationLocation = clean.length() <= 180 ? clean : clean.substring(0, 180);
        }
        if (nextEligibleDate != null) setNextEligibleDate(nextEligibleDate, now);
        if (source != null) sourceType = source;
        updatedAt = java.util.Objects.requireNonNull(now);
    }

    public void setNextEligibleDate(LocalDate date, Instant now) {
        if (date == null || date.isBefore(donationDate)) {
            throw new IllegalArgumentException("next eligible date cannot precede donation date");
        }
        nextEligibleDate = date;
        updatedAt = java.util.Objects.requireNonNull(now);
    }

    public Long getId() { return id; }
    public LocalDate getDonationDate() { return donationDate; }
    public String getDonationLocation() { return donationLocation; }
    public LocalDate getNextEligibleDate() { return nextEligibleDate; }
    public SourceType getSourceType() { return sourceType; }

    public enum SourceType { IMAGE, USER }
}
