package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxProperties;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient;
import com.aproject.aidriven.mymobilesecretary.integration.transport.TdxRoutingClient.TravelQuery;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 交通估算主入口:優先用 TDX 真實大眾運輸時間,失敗或未啟用時退回直線粗估。
 *
 * 關鍵規則:TDX 的任何錯誤都在這裡吃掉並 fallback——
 * 外部 API 故障不得讓可行性把關(乃至提醒核心)不可用。
 */
@Component
@Primary
public class CompositeTravelTimeEstimator implements TravelTimeEstimator {

    private static final Logger log = LoggerFactory.getLogger(CompositeTravelTimeEstimator.class);

    private final TdxRoutingClient tdxRoutingClient;
    private final TdxProperties tdxProperties;
    private final StraightLineTravelTimeEstimator fallback;
    private final FeasibilityProperties feasibilityProperties;

    public CompositeTravelTimeEstimator(TdxRoutingClient tdxRoutingClient,
                                        TdxProperties tdxProperties,
                                        StraightLineTravelTimeEstimator fallback,
                                        FeasibilityProperties feasibilityProperties) {
        this.tdxRoutingClient = tdxRoutingClient;
        this.tdxProperties = tdxProperties;
        this.fallback = fallback;
        this.feasibilityProperties = feasibilityProperties;
    }

    @Override
    public Duration estimate(double fromLat, double fromLon, double toLat, double toLon, Instant departAt) {
        if (!tdxProperties.usable()) {
            return fallback.estimate(fromLat, fromLon, toLat, toLon, departAt);
        }
        try {
            Duration transit = tdxRoutingClient.getTransitTravelTime(
                    TravelQuery.of(fromLat, fromLon, toLat, toLon, departAt));
            // TDX 回的是車上+等車時間;仍加轉場緩衝(出門、走到站牌)
            return transit.plus(feasibilityProperties.transferBuffer());
        } catch (Exception e) {
            log.warn("TDX travel time failed, falling back to straight-line estimate", e);
            return fallback.estimate(fromLat, fromLon, toLat, toLon, departAt);
        }
    }
}
