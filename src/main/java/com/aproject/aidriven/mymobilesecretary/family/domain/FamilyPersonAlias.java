package com.aproject.aidriven.mymobilesecretary.family.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Actor-private term that resolves to a stable family person profile. */
@Entity
@Table(name = "family_person_alias", uniqueConstraints = @UniqueConstraint(
        name = "uq_family_person_alias_owner_alias",
        columnNames = {"workspace_id", "created_by_user_id", "normalized_alias"}))
public class FamilyPersonAlias extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long personId;

    @Column(nullable = false, length = 80)
    private String alias;

    @Column(nullable = false, length = 80)
    private String normalizedAlias;

    @Column(nullable = false)
    private Instant createdAt;

    protected FamilyPersonAlias() {
    }

    public static FamilyPersonAlias create(Long personId, String alias,
                                           String normalizedAlias, Instant now) {
        FamilyPersonAlias value = new FamilyPersonAlias();
        value.personId = personId;
        value.alias = alias;
        value.normalizedAlias = normalizedAlias;
        value.createdAt = now;
        return value;
    }

    public Long getId() { return id; }
    public Long getPersonId() { return personId; }
    public String getAlias() { return alias; }
    public String getNormalizedAlias() { return normalizedAlias; }
    public Instant getCreatedAt() { return createdAt; }
}
