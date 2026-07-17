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
import java.util.EnumSet;

/** A person detail with explicit, fail-closed visibility. */
@Entity
@Table(name = "family_person_attribute", uniqueConstraints = @UniqueConstraint(
        name = "uq_family_person_attribute_key",
        columnNames = {"workspace_id", "created_by_user_id", "person_id", "attribute_key"}))
public class FamilyPersonAttribute extends WorkspaceOwnedEntity {

    public enum Key {
        NAME,
        GENDER,
        HEIGHT_CM,
        WEIGHT_KG,
        BIRTH_DATE,
        BLOOD_TYPE,
        SCHOOL,
        COMPANY,
        WORKPLACE,
        NOTE
    }

    public enum Visibility {
        PRIVATE,
        FAMILY
    }

    private static final EnumSet<Key> FAMILY_SHAREABLE = EnumSet.of(
            Key.NAME, Key.GENDER, Key.HEIGHT_CM, Key.WEIGHT_KG, Key.BIRTH_DATE,
            Key.SCHOOL, Key.COMPANY, Key.WORKPLACE);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribute_key", nullable = false, length = 40)
    private Key key;

    @Column(name = "attribute_value", nullable = false, length = 500)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FamilyPersonAttribute() {
    }

    public static FamilyPersonAttribute create(Long personId, Key key, String value,
                                               Visibility visibility, Instant now) {
        validateVisibility(key, visibility);
        FamilyPersonAttribute attribute = new FamilyPersonAttribute();
        attribute.personId = personId;
        attribute.key = key;
        attribute.value = value;
        attribute.visibility = visibility;
        attribute.createdAt = now;
        attribute.updatedAt = now;
        return attribute;
    }

    public void update(String value, Visibility visibility, Instant now) {
        validateVisibility(key, visibility);
        this.value = value;
        this.visibility = visibility;
        this.updatedAt = now;
    }

    public static boolean isFamilyShareable(Key key) {
        return FAMILY_SHAREABLE.contains(key);
    }

    private static void validateVisibility(Key key, Visibility visibility) {
        if (visibility == Visibility.FAMILY && !isFamilyShareable(key)) {
            throw new IllegalArgumentException(key + " cannot be shared with a family");
        }
    }

    public Long getId() { return id; }
    public Long getPersonId() { return personId; }
    public Key getKey() { return key; }
    public String getValue() { return value; }
    public Visibility getVisibility() { return visibility; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
