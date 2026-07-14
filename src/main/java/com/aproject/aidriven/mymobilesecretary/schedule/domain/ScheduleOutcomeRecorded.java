package com.aproject.aidriven.mymobilesecretary.schedule.domain;

/**
 * 行程結果已回報(跨模組事件:knowledge 據此累積地點緩衝統計)。
 *
 * @param scheduleItemId 行程 id
 * @param placeId        行程地點(無地點行程為 null,緩衝統計會略過)
 * @param onTime         是否準時
 * @param overrunMinutes 超時分鐘(準時為 null)
 */
public record ScheduleOutcomeRecorded(
        Long scheduleItemId,
        Long placeId,
        boolean onTime,
        Integer overrunMinutes
) {
}
