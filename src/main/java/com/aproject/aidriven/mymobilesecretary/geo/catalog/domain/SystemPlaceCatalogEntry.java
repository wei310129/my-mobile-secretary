package com.aproject.aidriven.mymobilesecretary.geo.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Installation-owned reference place. It is never a workspace custom Place. */
@Entity
@Table(name = "system_place_catalog")
public class SystemPlaceCatalogEntry {

    @Id
    @Column(name = "catalog_key", length = 120)
    private String catalogKey;

    @Column(name = "logical_key", nullable = false, length = 120)
    private String logicalKey;

    @Column(name = "logical_name", nullable = false, length = 200)
    private String logicalName;

    @Column(name = "normalized_logical_name", nullable = false, length = 200)
    private String normalizedLogicalName;

    @Column(name = "point_name", nullable = false, length = 200)
    private String pointName;

    @Column(name = "normalized_point_name", nullable = false, length = 200)
    private String normalizedPointName;

    @Column(length = 100)
    private String region;

    @Column(length = 300)
    private String address;

    private Double latitude;

    private Double longitude;

    @Column(length = 50)
    private String category;

    @Column(name = "source_name", nullable = false, length = 120)
    private String sourceName;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "catalogEntry", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    private List<SystemPlaceCatalogAlias> aliases = new ArrayList<>();

    protected SystemPlaceCatalogEntry() {
    }

    public static SystemPlaceCatalogEntry create(
            String catalogKey, String logicalKey, String logicalName, String pointName,
            String region, String address, Double latitude, Double longitude, String category,
            String sourceName, String sourceUrl, Instant updatedAt) {
        SystemPlaceCatalogEntry entry = new SystemPlaceCatalogEntry();
        entry.catalogKey = requireText(catalogKey, "catalogKey");
        entry.logicalKey = requireText(logicalKey, "logicalKey");
        entry.logicalName = requireText(logicalName, "logicalName");
        entry.normalizedLogicalName = normalize(entry.logicalName);
        entry.pointName = requireText(pointName, "pointName");
        entry.normalizedPointName = normalize(entry.pointName);
        entry.region = clean(region);
        entry.address = clean(address);
        entry.latitude = latitude;
        entry.longitude = longitude;
        entry.category = clean(category);
        entry.sourceName = requireText(sourceName, "sourceName");
        entry.sourceUrl = clean(sourceUrl);
        entry.active = true;
        entry.updatedAt = updatedAt;
        return entry;
    }

    public void addAlias(String alias) {
        String value = requireText(alias, "alias");
        aliases.add(SystemPlaceCatalogAlias.create(this, value));
    }

    public String getCatalogKey() { return catalogKey; }
    public String getLogicalKey() { return logicalKey; }
    public String getLogicalName() { return logicalName; }
    public String getPointName() { return pointName; }
    public String getRegion() { return region; }
    public String getAddress() { return address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getCategory() { return category; }
    public String getSourceName() { return sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static String requireText(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String normalize(String value) {
        return java.text.Normalizer.normalize(requireText(value, "value"),
                        java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
