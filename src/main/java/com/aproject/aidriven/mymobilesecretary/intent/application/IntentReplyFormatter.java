package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 將對話回覆整理成一致、適合聊天視窗閱讀的格式。
 *
 * <p>每個段落以可辨識的 emoji 開頭；段落內第二行之後一律是條列項目。
 * 格式化具冪等性，讓產生回覆與通道送出前都能安全套用。</p>
 */
public final class IntentReplyFormatter {

    private static final Pattern BLOCK_SEPARATOR = Pattern.compile("\\R\\s*\\R+");
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");
    private static final Pattern LEADING_EMOJI = Pattern.compile(
            "^[\\p{So}\\p{Sk}]\\uFE0F?(?:\\u200D[\\p{So}\\p{Sk}]\\uFE0F?)*\\s*");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[-*•]\\s*|\\d+[.)、]\\s*|[（(]\\d+[）)]\\s*).+");

    private IntentReplyFormatter() {
    }

    public static String format(IntentResult.Action action, String message) {
        return format(emojiFor(action), message);
    }

    public static String formatNotification(String title, String message) {
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String emoji;
        if (containsAny(normalizedTitle, "天氣", "降雨", "下雨", "weather")) {
            emoji = "🌦️";
        } else if (containsAny(normalizedTitle, "行程", "安排", "schedule")) {
            emoji = "📅";
        } else if (containsAny(normalizedTitle, "地點", "到達", "離開", "geofence")) {
            emoji = "📍";
        } else {
            emoji = "🔔";
        }
        return format(emoji, message);
    }

    public static String format(String defaultEmoji, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String normalized = message.replace("\r\n", "\n").replace('\r', '\n').strip();
        String[] rawBlocks = BLOCK_SEPARATOR.split(normalized);
        List<String> blocks = new ArrayList<>(rawBlocks.length);
        for (String rawBlock : rawBlocks) {
            String block = formatBlock(defaultEmoji, rawBlock);
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        return String.join("\n\n", blocks);
    }

    private static String formatBlock(String defaultEmoji, String rawBlock) {
        String[] rawLines = LINE_SEPARATOR.split(rawBlock.strip());
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String rawLine : rawLines) {
            String line = rawLine.strip();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }

        String first = lines.getFirst();
        if (!LEADING_EMOJI.matcher(first).find()) {
            first = emojiForBlock(defaultEmoji, first) + " " + first;
        }

        StringBuilder formatted = new StringBuilder(first);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            formatted.append('\n');
            if (!LIST_ITEM.matcher(line).matches()) {
                formatted.append("- ");
            }
            formatted.append(line);
        }
        return formatted.toString();
    }

    private static String emojiForBlock(String defaultEmoji, String firstLine) {
        if (containsAny(firstLine, "衝突", "逾期", "失敗", "錯誤", "風險", "來不及", "無法")) {
            return "⚠️";
        }
        if (containsAny(firstLine, "建議", "可考慮", "可以考慮", "不妨")) {
            return "💡";
        }
        if (containsAny(firstLine, "請回覆", "請選", "請確認", "請告訴", "要不要", "是否")
                || firstLine.endsWith("?") || firstLine.endsWith("？")) {
            return "❓";
        }
        if (containsAny(firstLine, "天氣", "降雨", "高溫")) {
            return "🌦️";
        }
        if (firstLine.contains("提醒")) {
            return "🔔";
        }
        if (containsAny(firstLine, "地點", "店家", "位置")) {
            return "📍";
        }
        if (containsAny(firstLine, "購物", "待買")) {
            return "🛒";
        }
        if (firstLine.contains("庫存")) {
            return "📦";
        }
        if (containsAny(firstLine, "價格", "單價")) {
            return "💰";
        }
        return defaultEmoji == null || defaultEmoji.isBlank() ? "💬" : defaultEmoji;
    }

    private static String emojiFor(IntentResult.Action action) {
        if (action == null) {
            return "💬";
        }
        String name = action.name();
        if (name.contains("RESTAURANT")) {
            return "🍽️";
        }
        if (name.contains("WEATHER")) {
            return "🌦️";
        }
        if (containsAny(name, "TRAVEL", "TRAFFIC", "ROUTE")) {
            return "🚗";
        }
        if (name.contains("FAMILY_NOTICE")) {
            return "👨‍👩‍👧‍👦";
        }
        if (name.contains("FAMILY_PERSON")) {
            return "👤";
        }
        if (name.contains("KNOWLEDGE")) {
            return "🧠";
        }
        if (name.contains("PRICE")) {
            return "💰";
        }
        if (name.contains("INVENTORY")) {
            return "📦";
        }
        if (containsAny(name, "SHOPPING", "ITEM")) {
            return "🛒";
        }
        if (containsAny(name, "PLACE", "LOCATION", "GEOFENCE")) {
            return "📍";
        }
        if (containsAny(name, "REMINDER", "MUTE")) {
            return "🔔";
        }
        if (containsAny(name, "SCHEDULE", "AGENDA", "AVAILABILITY", "FREE_SLOT",
                "CONNECTION", "OUTCOME")) {
            return "📅";
        }
        if (name.contains("FEEDBACK")) {
            return "🛠️";
        }
        if (name.contains("CLARIFICATION")) {
            return "❓";
        }
        if (name.contains("SOCIAL")) {
            return "👋";
        }
        if (name.contains("FALLBACK") || name.contains("UNAVAILABLE")) {
            return "⚠️";
        }
        if (name.contains("SUGGESTION")) {
            return "💡";
        }
        return "📋";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
