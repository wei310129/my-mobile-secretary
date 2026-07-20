package com.aproject.aidriven.mymobilesecretary.shared.time;

import java.util.List;

/** 中文口語時段到 24 小時制的單一規則來源。 */
public final class ChineseTimePeriod {

    public static final String NON_CAPTURING_REGEX =
            "(?:凌晨|上午|早上|中午|下午|晚上|黃昏|傍晚)";
    public static final String CAPTURING_REGEX =
            "(凌晨|上午|早上|中午|下午|晚上|黃昏|傍晚)";
    public static final List<String> TOKENS =
            List.of("凌晨", "上午", "早上", "中午", "下午", "晚上", "黃昏", "傍晚");

    private ChineseTimePeriod() {
    }

    public static int toTwentyFourHour(String period, int hour) {
        if (hour < 0 || hour > 23) {
            return hour;
        }
        if (isAm(period) && hour == 12) {
            return 0;
        }
        if (isPm(period) && hour < 12) {
            return hour + 12;
        }
        if ("中午".equals(period) && hour < 11) {
            return hour + 12;
        }
        return hour;
    }

    public static String leadingPeriod(String raw) {
        if (raw == null) return null;
        return TOKENS.stream().filter(raw::startsWith).findFirst().orElse(null);
    }

    public static String containedPeriod(String raw) {
        if (raw == null) return null;
        return TOKENS.stream().filter(raw::contains).findFirst().orElse(null);
    }

    private static boolean isAm(String period) {
        return "凌晨".equals(period) || "上午".equals(period) || "早上".equals(period);
    }

    private static boolean isPm(String period) {
        return "下午".equals(period) || "晚上".equals(period)
                || "黃昏".equals(period) || "傍晚".equals(period);
    }
}
