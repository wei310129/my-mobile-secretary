package com.aproject.aidriven.mymobilesecretary.family.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** One actor's private identity for a person; aliases express that actor's vocabulary. */
@Entity
@Table(name = "family_person_profile", uniqueConstraints = @UniqueConstraint(
        name = "uq_family_person_profile_key",
        columnNames = {"workspace_id", "created_by_user_id", "canonical_key"}))
public class FamilyPersonProfile extends WorkspaceOwnedEntity {

    public enum Role {
        SPOUSE,
        DAUGHTER,
        SON,
        OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String canonicalKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(nullable = false, length = 80)
    private String displayLabel;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FamilyPersonProfile() {
    }

    public static FamilyPersonProfile create(String canonicalKey, Role role,
                                              String displayLabel, Instant now) {
        FamilyPersonProfile profile = new FamilyPersonProfile();
        profile.canonicalKey = canonicalKey;
        profile.role = role;
        profile.displayLabel = displayLabel;
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }

    public Long getId() { return id; }
    public String getCanonicalKey() { return canonicalKey; }
    public Role getRole() { return role; }
    public String getDisplayLabel() { return displayLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
