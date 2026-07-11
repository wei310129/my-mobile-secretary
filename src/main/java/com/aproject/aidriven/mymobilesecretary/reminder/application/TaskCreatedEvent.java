package com.aproject.aidriven.mymobilesecretary.reminder.application;

/**
 * 任務建立事件(Phase 1 事件匯流排:Spring Events;之後演進到 Redis Streams/Kafka)。
 * 目前的訂閱者:knowledge 模組的自動綁定(品項比對 → 自動建 geofence 規則)。
 *
 * @param taskId 新任務 id
 * @param title  任務標題(供品項比對)
 */
public record TaskCreatedEvent(Long taskId, String title) {
}
