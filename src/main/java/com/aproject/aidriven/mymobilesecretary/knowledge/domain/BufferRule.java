package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 緩衝規則(習慣知識):某地點行程「計畫 vs 實際」差異的累積統計。
 *
 * 準時回報也要入樣本(計 0 分鐘):十次有九次準時的地點,平均超時被準時稀釋,
 * 緩衝自然變小;只記超時會高估。門檻與上限等「用多少」的判斷在 BufferRuleService,
 * 這裡只負責統計正確。
 */
@Entity
public class BufferRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long placeId;

    @Column(nullable = false)
    private int sampleCount;

    @Column(nullable = false)
    private int onTimeCount;

    @Column(nullable = false)
    private long totalOverrunMinutes;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected BufferRule() {
    }

    private BufferRule(Long placeId, Instant now) {
        this.placeId = placeId;
        this.sampleCount = 0;
        this.onTimeCount = 0;
        this.totalOverrunMinutes = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 為地點建立空白統計。 */
    public static BufferRule create(Long placeId, Instant now) {
        return new BufferRule(placeId, now);
    }

    /** 記一筆回報樣本;準時傳 0,超時傳分鐘數(負值視為 0,防禦上游錯誤)。 */
    public void recordSample(int overrunMinutes, Instant now) {
        int minutes = Math.max(overrunMinutes, 0);
        this.sampleCount++;
        if (minutes == 0) {
            this.onTimeCount++;
        }
        this.totalOverrunMinutes += minutes;
        this.updatedAt = now;
    }

    /** 平均超時分鐘(含準時樣本的稀釋,四捨五入)。 */
    public long averageOverrunMinutes() {
        if (sampleCount == 0) {
            return 0;
        }
        return Math.round((double) totalOverrunMinutes / sampleCount);
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public int getOnTimeCount() {
        return onTimeCount;
    }

    public long getTotalOverrunMinutes() {
        return totalOverrunMinutes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
