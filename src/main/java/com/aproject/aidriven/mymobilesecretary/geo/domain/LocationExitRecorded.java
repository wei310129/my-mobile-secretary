package com.aproject.aidriven.mymobilesecretary.geo.domain;

import java.time.Instant;

/**
 * GPS 離開事件已記錄(事件驅動:任一事件都可觸發規劃引擎重新評估)。
 *
 * 只帶原始座標,不帶「命中哪些地點」——各訂閱方關心的半徑不同
 * (結果追蹤用行程地點半徑,未來的到家偵測用住家半徑),各自向 geo 查詢。
 *
 * @param latitude   離開點緯度
 * @param longitude  離開點經度
 * @param occurredAt 手機端記錄的離開時間
 */
public record LocationExitRecorded(double latitude, double longitude, Instant occurredAt) {
}
