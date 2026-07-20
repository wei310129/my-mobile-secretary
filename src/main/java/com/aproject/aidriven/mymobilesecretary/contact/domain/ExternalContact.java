package com.aproject.aidriven.mymobilesecretary.contact.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Actor-private professional contact imported from an explicitly identified business card. */
@Entity
public class ExternalContact extends WorkspaceOwnedEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 180)
    private String canonicalKey;
    @Column(nullable = false, length = 120)
    private String displayName;
    @Column(length = 160)
    private String organizationName;
    @Column(length = 120)
    private String profession;
    @Column(length = 100)
    private String phoneNumber;
    @Column(length = 180)
    private String email;
    @Column(length = 300)
    private String address;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ExternalContact() {
    }

    public static ExternalContact create(String canonicalKey, String displayName,
                                         String organizationName, String profession,
                                         String phoneNumber, String email, String address,
                                         Instant now) {
        ExternalContact contact = new ExternalContact();
        contact.canonicalKey = required(canonicalKey, "contact key", 180);
        contact.displayName = required(displayName, "contact display name", 120);
        contact.organizationName = optional(organizationName, 160);
        contact.profession = optional(profession, 120);
        contact.phoneNumber = optional(phoneNumber, 100);
        contact.email = optional(email, 180);
        contact.address = optional(address, 300);
        contact.createdAt = now;
        contact.updatedAt = now;
        return contact;
    }

    public void mergeMissing(String organizationName, String profession, String phoneNumber,
                             String email, String address, Instant now) {
        if (this.organizationName == null) this.organizationName = optional(organizationName, 160);
        if (this.profession == null) this.profession = optional(profession, 120);
        if (this.phoneNumber == null) this.phoneNumber = optional(phoneNumber, 100);
        if (this.email == null) this.email = optional(email, 180);
        if (this.address == null) this.address = optional(address, 300);
        this.updatedAt = now;
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return optional(value, maximum);
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    public Long getId() { return id; }
    public String getCanonicalKey() { return canonicalKey; }
    public String getDisplayName() { return displayName; }
    public String getOrganizationName() { return organizationName; }
    public String getProfession() { return profession; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getEmail() { return email; }
    public String getAddress() { return address; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
