package com.aproject.aidriven.mymobilesecretary.utility.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One visible monthly row from a utility-bill history image. */
@Entity
public class UtilityBillRecord extends WorkspaceOwnedEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private UUID importBatchId;
    @Column(length = 120)
    private String provider;
    @Column(length = 120)
    private String locationLabel;
    @Column(nullable = false)
    private LocalDate billingMonth;
    private Integer usageKwh;
    private Integer amountTwd;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected UtilityBillRecord() {
    }

    public static UtilityBillRecord pending(UUID batchId, String provider, LocalDate billingMonth,
                                            Integer usageKwh, Integer amountTwd, Instant now) {
        if (batchId == null || billingMonth == null || billingMonth.getDayOfMonth() != 1
                || (usageKwh == null && amountTwd == null)) {
            throw new IllegalArgumentException("utility bill row is incomplete");
        }
        UtilityBillRecord record = new UtilityBillRecord();
        record.importBatchId = batchId;
        record.provider = clean(provider);
        record.billingMonth = billingMonth;
        record.usageKwh = nonNegative(usageKwh);
        record.amountTwd = nonNegative(amountTwd);
        record.status = Status.PENDING;
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public void confirm(String location, Instant now) {
        if (status != Status.PENDING || location == null || location.isBlank()) {
            throw new IllegalArgumentException("utility bill location is required");
        }
        locationLabel = clean(location);
        status = Status.CONFIRMED;
        updatedAt = now;
    }

    public void replaceMeasurements(Integer usage, Integer amount, Instant now) {
        if (status != Status.CONFIRMED || (usage == null && amount == null)) {
            throw new IllegalArgumentException("confirmed utility bill update is invalid");
        }
        usageKwh = nonNegative(usage);
        amountTwd = nonNegative(amount);
        updatedAt = now;
    }

    public void supersede(Instant now) {
        if (status == Status.PENDING) {
            status = Status.SUPERSEDED;
            updatedAt = now;
        }
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip().replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }

    public Long getId() { return id; }
    public UUID getImportBatchId() { return importBatchId; }
    public String getProvider() { return provider; }
    public String getLocationLabel() { return locationLabel; }
    public LocalDate getBillingMonth() { return billingMonth; }
    public Integer getUsageKwh() { return usageKwh; }
    public Integer getAmountTwd() { return amountTwd; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public enum Status { PENDING, CONFIRMED, SUPERSEDED }
}
