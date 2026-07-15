package com.aproject.aidriven.mymobilesecretary.geo.domain;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * geofence 規則:把「任務」綁到「地點」——進入(或離開)該地點半徑內就觸發提醒。
 *
 * 半徑規則:
 * - 下限 {@value #MIN_RADIUS_METERS} 公尺:iOS geofence 半徑太小會嚴重漏觸發,
 *   Core Location 實務上 100 公尺以下可靠度明顯下降,50 是可接受的極限。
 * - 上限 {@value #MAX_RADIUS_METERS} 公尺:太大則「到地點」失去意義且互相重疊。
 */
@Entity
public class GeofenceRule {

    public static final int MIN_RADIUS_METERS = 50;
    public static final int MAX_RADIUS_METERS = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long placeId;

    @Column(nullable = false)
    private int radiusMeters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TriggerType triggerType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected GeofenceRule() {
    }

    private GeofenceRule(Long taskId, Long placeId, int radiusMeters, TriggerType triggerType, Instant now) {
        this.taskId = taskId;
        this.placeId = placeId;
        this.radiusMeters = radiusMeters;
        this.triggerType = triggerType;
        this.enabled = true;
        this.createdAt = now;
    }

    /**
     * 建立 geofence 規則,預設啟用。
     * 半徑在 domain 再驗一次(API 層 Bean Validation 之外的第二道防線),
     * 確保任何呼叫路徑都不可能存入無效半徑。
     */
    public static GeofenceRule create(Long taskId, Long placeId, int radiusMeters,
                                      TriggerType triggerType, Instant now) {
        if (radiusMeters < MIN_RADIUS_METERS || radiusMeters > MAX_RADIUS_METERS) {
            throw new BusinessException(
                    "INVALID_RADIUS",
                    "radiusMeters must be between %d and %d, got %d"
                            .formatted(MIN_RADIUS_METERS, MAX_RADIUS_METERS, radiusMeters));
        }
        return new GeofenceRule(taskId, placeId, radiusMeters, triggerType, now);
    }

    /** 修改既有規則;半徑沿用與建立時相同的可靠度界線。 */
    public void change(Integer newRadiusMeters, TriggerType newTriggerType) {
        if (newRadiusMeters != null) {
            if (newRadiusMeters < MIN_RADIUS_METERS || newRadiusMeters > MAX_RADIUS_METERS) {
                throw new BusinessException("INVALID_RADIUS",
                        "radiusMeters must be between %d and %d, got %d".formatted(
                                MIN_RADIUS_METERS, MAX_RADIUS_METERS, newRadiusMeters));
            }
            this.radiusMeters = newRadiusMeters;
        }
        if (newTriggerType != null) {
            this.triggerType = newTriggerType;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public int getRadiusMeters() {
        return radiusMeters;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
