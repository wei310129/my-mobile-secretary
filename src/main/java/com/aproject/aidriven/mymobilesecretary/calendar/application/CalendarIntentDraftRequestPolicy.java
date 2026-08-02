package com.aproject.aidriven.mymobilesecretary.calendar.application;

/** Bounded user grammar for an explicit request to prepare, but not yet save, a calendar item. */
public final class CalendarIntentDraftRequestPolicy {

    private CalendarIntentDraftRequestPolicy() {}

    public static boolean isDraftOnly(String text) {
        String compact = text == null ? "" : text.replaceAll("[\\s　]+", "");
        return containsAny(compact, "保留草稿", "先留草稿", "先幫我留草稿", "只留草稿")
                || containsAny(compact, "不要直接建立", "不要建立正式", "先不要建立正式")
                || containsAny(compact, "先準備提案", "先整理提案", "先不要排進行事曆");
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }
}
