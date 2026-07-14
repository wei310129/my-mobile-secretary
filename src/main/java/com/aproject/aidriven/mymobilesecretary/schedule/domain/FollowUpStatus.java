package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 行程結果追蹤詢問的狀態機:
 *
 * <pre>
 * SCHEDULED ──到期發問──→ ASKED ──使用者回報──→ ANSWERED
 * </pre>
 *
 * 單向前進,不回頭:問過就不再排程,答過就不再問。
 */
public enum FollowUpStatus {

    /** 已排定,等到期發問。 */
    SCHEDULED,
    /** 已發問,等使用者回報。 */
    ASKED,
    /** 使用者已回報結果(終止)。 */
    ANSWERED;

    private Set<FollowUpStatus> allowedNext() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(ASKED, ANSWERED);
            case ASKED -> EnumSet.of(ANSWERED);
            case ANSWERED -> EnumSet.noneOf(FollowUpStatus.class);
        };
    }

    /** 判斷是否允許轉換到目標狀態。 */
    public boolean canTransitionTo(FollowUpStatus target) {
        return allowedNext().contains(target);
    }
}
