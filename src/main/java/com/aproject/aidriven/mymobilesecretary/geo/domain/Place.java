package com.aproject.aidriven.mymobilesecretary.geo.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 地點:geofence 提醒的空間錨點。
 *
 * Phase 1A 以純經緯度欄位儲存;Phase 1B 的空間查詢用 PostGIS
 * ST_MakePoint(longitude, latitude) 動態組 geography,不需要另外的 geometry 欄位。
 */
@Entity
public class Place extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 300)
    private String address;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** 地點類型先用自由文字(如「超市」「菜市場」),累積真實用法後再收斂成 enum。 */
    @Column(length = 50)
    private String type;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected Place() {
    }

    private Place(String name, String address, double latitude, double longitude, String type, Instant now) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.type = type;
        this.createdAt = now;
    }

    /** 建立地點。經緯度範圍由 API 層的 Bean Validation 把關。 */
    public static Place create(String name, String address, double latitude, double longitude,
                               String type, Instant now) {
        return new Place(name, address, latitude, longitude, type, now);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
