package com.aproject.aidriven.mymobilesecretary.geo.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 一筆離散的位置事件。
 *
 * 關鍵規則:系統只接收離散事件(進入/離開/手動回報),
 * 不得假設有連續 GPS 軌跡——這是電池與隱私的底線(architecture.md §12)。
 */
@Entity
public class LocationEvent extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private LocationEventType eventType;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** 事件在裝置上發生的時間(客戶端回報,可能與伺服器收到時間不同)。 */
    @Column(nullable = false)
    private Instant occurredAt;

    /** 事件來源,例如 api-simulated / ios。 */
    @Column(length = 50)
    private String source;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected LocationEvent() {
    }

    private LocationEvent(LocationEventType eventType, double latitude, double longitude,
                          Instant occurredAt, String source, Instant now) {
        this.eventType = eventType;
        this.latitude = latitude;
        this.longitude = longitude;
        this.occurredAt = occurredAt;
        this.source = source;
        this.createdAt = now;
    }

    /** 記錄一筆位置事件;occurredAt 未提供時以伺服器時間代替。 */
    public static LocationEvent record(LocationEventType eventType, double latitude, double longitude,
                                       Instant occurredAt, String source, Instant now) {
        return new LocationEvent(eventType, latitude, longitude,
                occurredAt == null ? now : occurredAt, source, now);
    }

    /**
     * geofence 比對時的有效觸發方向:MANUAL_PING 語意是「人在這裡」,視同 ENTER。
     */
    public TriggerType effectiveTriggerType() {
        return eventType == LocationEventType.EXIT ? TriggerType.EXIT : TriggerType.ENTER;
    }

    public Long getId() {
        return id;
    }

    public LocationEventType getEventType() {
        return eventType;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
