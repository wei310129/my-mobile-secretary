package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Actor-scoped route operation minutes; no place, address, or conversation text is stored. */
@Entity
@Table(name = "actor_route_operation_preference")
public class ActorRouteOperationPreference extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer generalBeforeMinutes;
    private Integer generalAfterMinutes;
    private Integer parkingMinutes;
    private Integer rideHailWaitMinutes;

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

    protected ActorRouteOperationPreference() {}

    public static ActorRouteOperationPreference create(Instant now) {
        ActorRouteOperationPreference preference = new ActorRouteOperationPreference();
        preference.active = true;
        preference.revision = 1;
        preference.createdAt = Objects.requireNonNull(now, "now");
        preference.updatedAt = now;
        return preference;
    }

    public void setGeneral(int beforeMinutes, int afterMinutes, Instant now) {
        int before = validMinutes(beforeMinutes);
        int after = validMinutes(afterMinutes);
        if (active
                && Objects.equals(generalBeforeMinutes, before)
                && Objects.equals(generalAfterMinutes, after)) {
            return;
        }
        generalBeforeMinutes = before;
        generalAfterMinutes = after;
        changed(now);
    }

    public void setParking(int minutes, Instant now) {
        int value = validMinutes(minutes);
        if (active && Objects.equals(parkingMinutes, value)) return;
        parkingMinutes = value;
        changed(now);
    }

    public void setRideHailWait(int minutes, Instant now) {
        int value = validMinutes(minutes);
        if (active && Objects.equals(rideHailWaitMinutes, value)) return;
        rideHailWaitMinutes = value;
        changed(now);
    }

    private void changed(Instant now) {
        active = true;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static int validMinutes(int minutes) {
        if (minutes < 0 || minutes > 240) {
            throw new IllegalArgumentException("Route operation minutes must be between 0 and 240");
        }
        return minutes;
    }

    public Long getId() {
        return id;
    }

    public Integer getGeneralBeforeMinutes() {
        return generalBeforeMinutes;
    }

    public Integer getGeneralAfterMinutes() {
        return generalAfterMinutes;
    }

    public Integer getParkingMinutes() {
        return parkingMinutes;
    }

    public Integer getRideHailWaitMinutes() {
        return rideHailWaitMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public long getRevision() {
        return revision;
    }
}
