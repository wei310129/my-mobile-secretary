package com.aproject.aidriven.mymobilesecretary.venue.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A user-confirmed venue advisory that may be surfaced on a future visit. */
@Entity
public class VenueVisitInformation extends WorkspaceOwnedEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 160)
    private String venueName;
    @Column(length = 160)
    private String normalizedVenue;
    @Column(nullable = false, length = 200)
    private String subject;
    @Column(nullable = false, length = 1200)
    private String details;
    @Column(nullable = false)
    private boolean reservationRequired;
    private Integer minimumGroupSize;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SourceType sourceType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected VenueVisitInformation() {
    }

    public static VenueVisitInformation create(String venueName, String subject, String details,
                                               boolean reservationRequired,
                                               Integer minimumGroupSize,
                                               SourceType sourceType, Instant now) {
        if (blank(subject) || blank(details) || sourceType == null || now == null) {
            throw new IllegalArgumentException("venue visit information is incomplete");
        }
        VenueVisitInformation information = new VenueVisitInformation();
        information.subject = clean(subject, 200);
        information.details = clean(details, 1200);
        information.reservationRequired = reservationRequired;
        information.minimumGroupSize = minimumGroupSize == null || minimumGroupSize <= 0
                ? null : minimumGroupSize;
        information.sourceType = sourceType;
        information.status = Status.PENDING_VENUE;
        information.createdAt = now;
        information.updatedAt = now;
        if (!blank(venueName)) {
            information.confirmVenue(venueName, now);
        }
        return information;
    }

    public void confirmVenue(String venueName, Instant now) {
        if (blank(venueName) || status == Status.SUPERSEDED) {
            throw new IllegalArgumentException("venue name is required");
        }
        this.venueName = clean(venueName, 160);
        this.normalizedVenue = normalize(this.venueName);
        this.status = Status.ACTIVE;
        this.updatedAt = now;
    }

    public void supersede(Instant now) {
        status = Status.SUPERSEDED;
        updatedAt = now;
    }

    public boolean matches(String value) {
        if (status != Status.ACTIVE || blank(value)) return false;
        String normalized = normalize(value);
        return normalized.contains(normalizedVenue) || normalizedVenue.contains(normalized);
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.strip().toLowerCase(java.util.Locale.ROOT)
                .replace('臺', '台').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String clean(String value, int maximum) {
        String cleaned = value.strip().replaceAll("\\s+", " ");
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public Long getId() { return id; }
    public String getVenueName() { return venueName; }
    public String getNormalizedVenue() { return normalizedVenue; }
    public String getSubject() { return subject; }
    public String getDetails() { return details; }
    public boolean isReservationRequired() { return reservationRequired; }
    public Integer getMinimumGroupSize() { return minimumGroupSize; }
    public SourceType getSourceType() { return sourceType; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public enum SourceType { IMAGE, TEXT }
    public enum Status { PENDING_VENUE, ACTIVE, SUPERSEDED }
}
