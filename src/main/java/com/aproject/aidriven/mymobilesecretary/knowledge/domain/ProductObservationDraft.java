package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Short-lived image facts awaiting an explicit user-selected purpose. */
@Entity
public class ProductObservationDraft extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 180) private String productName;
    @Column(length = 120) private String brandName;
    @Column(length = 120) private String colorName;
    @Column(length = 255) private String sourceTitle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected ProductObservationDraft() {}

    public static ProductObservationDraft create(String productName, String brandName,
                                                  String colorName, String sourceTitle,
                                                  Instant now, Instant expiresAt) {
        if (blank(productName) && blank(brandName) && blank(colorName)) {
            throw new IllegalArgumentException("at least one product fact is required");
        }
        ProductObservationDraft draft = new ProductObservationDraft();
        draft.productName = clean(productName, 180);
        draft.brandName = clean(brandName, 120);
        draft.colorName = clean(colorName, 120);
        draft.sourceTitle = clean(sourceTitle, 255);
        draft.status = Status.PENDING_PURPOSE;
        draft.createdAt = now;
        draft.updatedAt = now;
        draft.expiresAt = expiresAt;
        return draft;
    }

    public void resolve(Instant now) {
        status = Status.RESOLVED;
        updatedAt = now;
    }

    public void extendRetention(Instant now, Instant newExpiresAt) {
        if (status != Status.PENDING_PURPOSE || newExpiresAt == null
                || !newExpiresAt.isAfter(now)) {
            throw new IllegalStateException("only a pending product draft can be extended");
        }
        updatedAt = now;
        expiresAt = newExpiresAt;
    }

    public String displayName() {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        if (!blank(brandName)) parts.add(brandName);
        if (!blank(productName)) parts.add(productName);
        if (!blank(colorName)) parts.add(colorName);
        return String.join(" ", parts);
    }

    private static String clean(String value, int max) {
        if (blank(value)) return null;
        String clean = value.strip().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public Long getId() { return id; }
    public String getProductName() { return productName; }
    public String getBrandName() { return brandName; }
    public String getColorName() { return colorName; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public enum Status { PENDING_PURPOSE, RESOLVED }
}
