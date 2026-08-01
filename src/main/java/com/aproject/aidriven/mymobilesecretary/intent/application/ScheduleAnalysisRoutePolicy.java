package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Conservative Java pre-route for the bounded schedule-analysis model.
 *
 * <p>The grouped markers only decide which interpreter may inspect the utterance. They never
 * select a business object, compute an answer, or authorize a mutation.</p>
 */
@Component
final class ScheduleAnalysisRoutePolicy {

    boolean matches(String text) {
        String value = normalize(text);
        return busiestDayShape(value) || longestItemShape(value) || adjacentGapShape(value);
    }

    private static boolean busiestDayShape(String text) {
        boolean comparative = containsAny(text,
                "最忙", "最滿", "最擠", "最多", "最繁忙", "最忙碌");
        boolean day = containsAny(text,
                "哪天", "哪一天", "日子", "日期", "一天", "七天", "每日", "每天");
        boolean scheduleLoad = containsAny(text,
                "行程", "活動", "安排", "排", "負荷", "忙");
        return comparative && day && scheduleLoad;
    }

    private static boolean longestItemShape(String text) {
        boolean durationComparison = containsAny(text,
                "最長", "最久", "耗時最", "時間最長", "占時間最", "佔時間最");
        boolean item = containsAny(text,
                "行程", "活動", "項目", "會議", "課程", "預約",
                "哪筆", "那筆", "這筆", "該筆", "一筆", "哪個", "那個", "這個", "該個");
        return durationComparison && item;
    }

    private static boolean adjacentGapShape(String text) {
        boolean gap = containsAny(text,
                "空檔", "空擋", "空閒", "空白時間", "空多久", "剩多久", "可用時間");
        boolean bothSides = text.contains("前後")
                || (containsAny(text, "前面", "之前", "前一段", "左邊", "左側")
                && containsAny(text, "後面", "之後", "後一段", "右邊", "右側"));
        boolean scheduleReference = containsAny(text,
                "行程", "活動", "項目", "會議", "課程", "預約",
                "哪筆", "那筆", "這筆", "該筆", "哪個", "那個", "這個", "該個", "它");
        return gap && bothSides && scheduleReference;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,;；。！？!?：:、]", "");
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
