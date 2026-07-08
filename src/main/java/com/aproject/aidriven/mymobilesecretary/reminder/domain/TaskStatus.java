package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任務狀態機(architecture.md §10):
 *
 * <pre>
 * CREATED → SCHEDULED → REMINDED → CONFIRMED
 *                          └→ ESCALATED(未回應,升級再提醒)
 * 任何非終止狀態皆可 → CANCELED
 * </pre>
 *
 * 關鍵規則:合法轉換集中定義在這裡,domain 以外的程式不得自行改 status,
 * 確保「狀態不能非法跳轉」在單一位置被守住。
 */
public enum TaskStatus {

    /** 剛建立,尚未排入任何提醒。 */
    CREATED,
    /** 已排入提醒排程(時間型或地點型)。 */
    SCHEDULED,
    /** 已送出提醒,等待使用者回報。 */
    REMINDED,
    /** 提醒後未回應,已升級再提醒。 */
    ESCALATED,
    /** 使用者已確認完成(終止狀態)。 */
    CONFIRMED,
    /** 使用者取消,不再追蹤(終止狀態)。 */
    CANCELED;

    /** 各狀態允許轉入的下一步。CONFIRMED/CANCELED 是終止狀態,無合法轉換。 */
    private Set<TaskStatus> allowedNext() {
        return switch (this) {
            // CREATED 允許直接 CONFIRMED:使用者可能在任何提醒發生前就自行完成
            case CREATED -> EnumSet.of(SCHEDULED, REMINDED, CONFIRMED, CANCELED);
            case SCHEDULED -> EnumSet.of(REMINDED, CONFIRMED, CANCELED);
            case REMINDED -> EnumSet.of(CONFIRMED, ESCALATED, CANCELED);
            // ESCALATED → ESCALATED:允許重複升級(第 2、3 次再提醒)
            case ESCALATED -> EnumSet.of(CONFIRMED, ESCALATED, CANCELED);
            case CONFIRMED, CANCELED -> EnumSet.noneOf(TaskStatus.class);
        };
    }

    /** 判斷是否允許轉換到目標狀態。 */
    public boolean canTransitionTo(TaskStatus target) {
        return allowedNext().contains(target);
    }
}
