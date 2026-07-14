package com.aproject.aidriven.mymobilesecretary.schedule.domain;

/**
 * 行程超時的原因分類(開發計畫第 14 節第 8 點的固定選項)。
 * 之後緩衝規則依原因分類累積「計畫 vs 實際」差異。
 */
public enum OutcomeReason {

    /** 會議/活動本身超時。 */
    MEETING_OVERRUN,
    /** 交通意外狀況。 */
    TRAFFIC_INCIDENT,
    /** 上下班尖峰。 */
    RUSH_HOUR,
    /** 其他(細節放 note)。 */
    OTHER
}
