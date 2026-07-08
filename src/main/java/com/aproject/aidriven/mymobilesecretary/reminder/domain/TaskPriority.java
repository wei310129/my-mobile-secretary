package com.aproject.aidriven.mymobilesecretary.reminder.domain;

/**
 * 任務優先度。Phase 1 只影響顯示排序,之後 planner 會用它決定提醒策略。
 */
public enum TaskPriority {
    LOW,
    NORMAL,
    HIGH
}
