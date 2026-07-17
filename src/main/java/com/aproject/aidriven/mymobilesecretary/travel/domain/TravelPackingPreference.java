package com.aproject.aidriven.mymobilesecretary.travel.domain;

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
import jakarta.persistence.UniqueConstraint;

/** A user's long-term packing suggestion preference, isolated from other household members. */
@Entity
@Table(name = "travel_packing_preference", uniqueConstraints = @UniqueConstraint(
        name = "uq_travel_packing_preference_actor_item",
        columnNames = {"workspace_id", "created_by_user_id", "normalized_item"}))
public class TravelPackingPreference extends WorkspaceOwnedEntity {

    public enum Preference {
        ALWAYS_SUGGEST,
        NEVER_SUGGEST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String itemName;

    @Column(nullable = false, length = 100)
    private String normalizedItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Preference preference;

    @Column(length = 300)
    private String reason;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TravelPackingPreference() {
    }

    public static TravelPackingPreference create(String itemName, String normalizedItem,
                                                  Preference preference, String reason,
                                                  Instant now) {
        TravelPackingPreference value = new TravelPackingPreference();
        value.update(itemName, normalizedItem, preference, reason, now);
        return value;
    }

    public void update(String itemName, String normalizedItem, Preference preference,
                       String reason, Instant now) {
        this.itemName = itemName;
        this.normalizedItem = normalizedItem;
        this.preference = preference;
        this.reason = reason;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getItemName() { return itemName; }
    public String getNormalizedItem() { return normalizedItem; }
    public Preference getPreference() { return preference; }
    public String getReason() { return reason; }
    public Instant getUpdatedAt() { return updatedAt; }
}
