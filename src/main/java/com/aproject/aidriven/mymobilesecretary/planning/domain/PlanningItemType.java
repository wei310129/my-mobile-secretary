package com.aproject.aidriven.mymobilesecretary.planning.domain;

/**
 * 使用者可感知的規劃項目類別，由資料要求由鬆到緊排列。
 *
 * <p>這是跨既有 draft、task 與 schedule aggregate 的共同語意，不要求 JPA entity
 * 彼此繼承。各 aggregate 仍保有自己的狀態機，跨類別轉換則統一經過
 * {@link PlanningItemTransitionPolicy} 驗證。</p>
 */
public enum PlanningItemType {
    DRAFT("草稿", false, false, false, false),
    TODO("待辦事項", true, false, false, true),
    SCHEDULE_REMINDER("行程提醒", true, true, false, true),
    SCHEDULE("行程", true, true, true, true),
    KNOWLEDGE("知識紀錄", true, false, false, false);

    private final String displayName;
    private final boolean persistent;
    private final boolean calendarVisible;
    private final boolean exclusive;
    private final boolean completable;

    PlanningItemType(String displayName, boolean persistent,
                     boolean calendarVisible, boolean exclusive, boolean completable) {
        this.displayName = displayName;
        this.persistent = persistent;
        this.calendarVisible = calendarVisible;
        this.exclusive = exclusive;
        this.completable = completable;
    }

    public String displayName() {
        return displayName;
    }

    public boolean persistent() {
        return persistent;
    }

    public boolean calendarVisible() {
        return calendarVisible;
    }

    public boolean exclusive() {
        return exclusive;
    }

    public boolean completable() {
        return completable;
    }
}
