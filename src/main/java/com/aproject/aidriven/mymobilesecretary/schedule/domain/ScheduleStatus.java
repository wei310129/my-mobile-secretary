package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 行程狀態機(要可行才放行的核心):
 *
 * <pre>
 * PROPOSED ──可行──→ CONFIRMED ──→ COMPLETED
 *    │  │                │
 *    │  ├─不可行且使用者放棄─→ REJECTED      └──→ CANCELED
 *    │  └─暫無想法─→ PENDING ──(想安排了)──→ PROPOSED
 *    └─改時間─→ PROPOSED(重新驗算)
 * </pre>
 *
 * 關鍵規則:只有 CONFIRMED 的行程是「真的承諾」,可行性引擎只拿 CONFIRMED 當基準。
 */
public enum ScheduleStatus {

    /** 已提出,等可行性驗算或使用者決定。 */
    PROPOSED,
    /** 已確認(可行放行,或使用者看過警告後強制確認)。 */
    CONFIRMED,
    /** 暫無想法,停在 pending 池等空閒時再問。 */
    PENDING,
    /** 不可行且使用者放棄(終止)。 */
    REJECTED,
    /** 已確認的行程被取消(終止)。 */
    CANCELED,
    /** 已完成(終止;Phase 3 的結果追蹤會用)。 */
    COMPLETED;

    private Set<ScheduleStatus> allowedNext() {
        return switch (this) {
            // PROPOSED → PROPOSED:改時間重新驗算
            case PROPOSED -> EnumSet.of(PROPOSED, CONFIRMED, PENDING, REJECTED);
            // CONFIRMED → PROPOSED:已確認行程改時間,要重新過可行性把關
            case CONFIRMED -> EnumSet.of(PROPOSED, CANCELED, COMPLETED);
            case PENDING -> EnumSet.of(PROPOSED, CANCELED);
            case REJECTED, CANCELED, COMPLETED -> EnumSet.noneOf(ScheduleStatus.class);
        };
    }

    /** 判斷是否允許轉換到目標狀態。 */
    public boolean canTransitionTo(ScheduleStatus target) {
        return allowedNext().contains(target);
    }
}
