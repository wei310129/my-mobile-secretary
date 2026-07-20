package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

/** Stable, user-queryable categories for consumption records. */
public enum ExpenseCategory {
    FOOD("餐飲"),
    BEVERAGE("飲品"),
    HOUSEHOLD("生活用品"),
    EDUCATION("教育學習"),
    CHILDCARE("育兒"),
    ENTERTAINMENT("娛樂"),
    TRANSPORT("交通"),
    HEALTHCARE("醫療保健"),
    CLOTHING("服飾"),
    LUXURY("精品首飾"),
    ELECTRONICS("家電數位"),
    HOUSING("居家"),
    WORK("工作"),
    TAX("稅款"),
    OTHER("其他"),
    UNKNOWN("未分類");

    private final String displayName;

    ExpenseCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
