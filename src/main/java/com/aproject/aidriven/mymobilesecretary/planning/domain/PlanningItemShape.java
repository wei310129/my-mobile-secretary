package com.aproject.aidriven.mymobilesecretary.planning.domain;

import java.time.Instant;

/** 轉換目標的可驗證資料形狀；不含任何 Web 或 LLM 型別。 */
public record PlanningItemShape(
        String title,
        Instant startAt,
        Instant endAt,
        boolean actorPresenceRequired,
        boolean conflictsChecked,
        boolean feasibilityChecked) {
}
