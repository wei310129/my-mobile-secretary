package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.time.Duration;
import java.time.Instant;

/**
 * 點對點交通時間估算的統一介面。
 * 可行性引擎只認這個介面,不管背後是 TDX 真實路線還是直線粗估。
 */
public interface TravelTimeEstimator {

    /**
     * 估算從 (fromLat, fromLon) 到 (toLat, toLon) 的移動時間(含轉場緩衝)。
     *
     * @param departAt 出發時間(大眾運輸班次與時段有關)
     */
    Duration estimate(double fromLat, double fromLon, double toLat, double toLon, Instant departAt);

    /**
     * 回傳可供 Java policy 判斷的 typed evidence。既有 test/custom estimator 預設視為已驗證路線來源；
     * production approximation 必須覆寫並標成 {@link EvidenceQuality#APPROXIMATION}。
     */
    default TravelTimeEvidence estimateEvidence(
            double fromLat, double fromLon, double toLat, double toLon, Instant departAt) {
        return TravelTimeEvidence.routed(
                estimate(fromLat, fromLon, toLat, toLon, departAt),
                EvidenceSource.CUSTOM_ROUTED);
    }

    enum EvidenceQuality {
        ROUTED,
        APPROXIMATION
    }

    enum EvidenceSource {
        TDX_TRANSIT,
        GOOGLE_ROUTES,
        STRAIGHT_LINE,
        CUSTOM_ROUTED,
        UNAVAILABLE
    }

    record TravelTimeEvidence(
            Duration duration,
            EvidenceQuality quality,
            EvidenceSource source) {

        public TravelTimeEvidence {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("travel duration must be non-negative");
            }
            if (quality == null || source == null) {
                throw new IllegalArgumentException("travel evidence quality and source are required");
            }
            if (quality == EvidenceQuality.APPROXIMATION
                    && source != EvidenceSource.STRAIGHT_LINE) {
                throw new IllegalArgumentException(
                        "only straight-line evidence may be marked as approximation");
            }
            if (quality == EvidenceQuality.ROUTED
                    && source == EvidenceSource.STRAIGHT_LINE) {
                throw new IllegalArgumentException(
                        "straight-line evidence cannot be marked as routed");
            }
        }

        public static TravelTimeEvidence routed(Duration duration, EvidenceSource source) {
            return new TravelTimeEvidence(duration, EvidenceQuality.ROUTED, source);
        }

        public static TravelTimeEvidence approximation(Duration duration) {
            return new TravelTimeEvidence(
                    duration, EvidenceQuality.APPROXIMATION, EvidenceSource.STRAIGHT_LINE);
        }

        public boolean supportsFeasibilityClaim() {
            return quality == EvidenceQuality.ROUTED;
        }
    }
}
