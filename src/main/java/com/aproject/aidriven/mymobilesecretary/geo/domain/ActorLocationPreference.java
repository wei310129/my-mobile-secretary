package com.aproject.aidriven.mymobilesecretary.geo.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Long-lived actor preference that references an already confirmed, actor-owned place. */
@Entity
@Table(name = "actor_location_preference")
public class ActorLocationPreference extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_kind", nullable = false, length = 20, updatable = false)
    private ActorLocationKind locationKind;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long revision;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ActorLocationPreference() {}

    public static ActorLocationPreference home(Long placeId, Instant now) {
        ActorLocationPreference preference = new ActorLocationPreference();
        preference.locationKind = ActorLocationKind.HOME;
        preference.placeId = Objects.requireNonNull(placeId, "placeId");
        preference.active = true;
        preference.revision = 1;
        preference.createdAt = Objects.requireNonNull(now, "now");
        preference.updatedAt = now;
        return preference;
    }

    public void activateAt(Long confirmedPlaceId, Instant now) {
        Long nextPlaceId = Objects.requireNonNull(confirmedPlaceId, "confirmedPlaceId");
        Instant changedAt = Objects.requireNonNull(now, "now");
        if (active && nextPlaceId.equals(placeId)) return;
        placeId = nextPlaceId;
        active = true;
        revision++;
        updatedAt = changedAt;
    }

    public void disable(Instant now) {
        if (!active) return;
        active = false;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public Long getId() {
        return id;
    }

    public ActorLocationKind getLocationKind() {
        return locationKind;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public boolean isActive() {
        return active;
    }

    public long getRevision() {
        return revision;
    }
}
