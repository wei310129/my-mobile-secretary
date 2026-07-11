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
}
