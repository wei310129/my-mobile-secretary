package com.aproject.aidriven.mymobilesecretary.reminder.domain;

/**
 * 單次提醒的狀態。Phase 1C 的提醒引擎會驅動這些狀態。
 */
public enum ReminderStatus {
    /** 已觸發並送出(或準備送出)。 */
    TRIGGERED,
    /** 使用者已確認收到並處理。 */
    CONFIRMED,
    /** 逾時未確認,已升級再提醒。 */
    ESCALATED
}
