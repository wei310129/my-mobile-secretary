package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-only travel intake. It classifies the outing and asks for the minimum information needed
 * before schedules, tasks, or geofences may be proposed. Product feedback and quoted examples are
 * deliberately excluded so a long feature proposal can never execute its embedded sample trip.
 */
@Service
public class TravelPlanningIntakeService {

    private static final Pattern DATE_RANGE = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})(?:日)?(?:-|－|—|～|~|到|至)(\\d{1,2})日?");
    private static final List<String> FOREIGN_DESTINATIONS = List.of(
            "日本", "韓國", "泰國", "越南", "新加坡", "馬來西亞", "香港", "澳門",
            "美國", "加拿大", "英國", "法國", "德國", "義大利", "澳洲", "紐西蘭");

    private final int outingThresholdMinutes;

    public TravelPlanningIntakeService(
            @Value("${app.travel.outing-threshold-minutes:35}") int outingThresholdMinutes) {
        this.outingThresholdMinutes = outingThresholdMinutes;
    }

    public Optional<IntentResult> answer(String text) {
        String normalized = normalize(text);
        if (!isSafeStandaloneRequest(normalized) || !looksLikeTrip(normalized)) {
            return Optional.empty();
        }
        return Optional.of(intakeNormalized(normalized));
    }

    /** Used when the interpreter has already classified the sentence as a standalone trip plan. */
    public IntentResult intake(String text) {
        return intakeNormalized(normalize(text));
    }

    private IntentResult intakeNormalized(String text) {
        if (isNearbyLeisure(text)) {
            return result("🌳 這次先歸類為住家附近的日常休閒。\n"
                    + "- 不納入國內出遊或出國旅遊\n\n"
                    + "❓ 要我協助安排時，請告訴我日期、出發時間和公園名稱。");
        }

        String destination = foreignDestination(text).orElse(null);
        if (isInternational(text, destination)) {
            return internationalIntake(text, destination);
        }
        if (isFamilyOuting(text)) {
            return result("👨‍👩‍👧 親子出遊規劃已開始。\n"
                    + "- 類型：親子／家庭出遊\n\n"
                    + "❓ 請先補目的地、出發日期與時間、去回交通方式。\n"
                    + "- 接著可整理親子用品清單、門票與有期限的準備任務\n"
                    + "- 最後會先讓你確認，再建立行程、待辦或地點提醒");
        }
        if (isDomestic(text)) {
            return result("🚆 國內出遊規劃已開始。\n"
                    + "- 交通線索：" + transport(text).orElse("尚未提供") + "\n\n"
                    + "❓ 請先補目的地、出發日期與時間、回程時間。\n"
                    + "- 接著可整理車站接駁、行李清單與有期限的準備任務\n"
                    + "- 最後會先讓你確認，再建立行程、待辦或地點提醒");
        }
        if (containsAny(text, "開車", "騎車") && containsAny(text, "出去玩", "出遊", "旅行")) {
            return result("🚗 目前知道你想自行開車／騎車出遊。\n"
                    + "- 單程約 " + outingThresholdMinutes + " 分鐘以上，才會預設歸類為出遊\n\n"
                    + "❓ 目的地是哪裡，預估單程多久？\n"
                    + "- 沒有目的地或車程時，我不會自行猜測或建立資料");
        }
        return result("🧭 出遊規劃已開始，但目前還不能判斷類型。\n"
                + "- 可分類：國內出遊、親子出遊、出國旅遊、住家附近日常休閒\n\n"
                + "❓ 請先告訴我目的地、日期與交通方式。\n"
                + "- 資料齊全後會先讓你確認，再建立行程、待辦或地點提醒");
    }

    private IntentResult internationalIntake(String text, String destination) {
        String dates = dateRange(text).orElse("尚未提供");
        String vehicle = transport(text).orElse("尚未提供");
        return result("🛳️ 出國旅遊規劃已開始。\n"
                + "- 日期：" + dates + "\n"
                + "- 目的地：" + (destination == null ? "尚未提供" : destination) + "\n"
                + "- 主要交通：" + vehicle + "\n\n"
                + "❓ 請補齊出發時間與地點、如何前往出發地、回程抵達時間與返家方式。\n"
                + "- 接著可整理證件／行李清單，以及各項準備任務與期限\n"
                + "- 可為港口、機場或中途點規劃較大範圍的地點提醒\n"
                + "- 所有行程、待辦與提醒都會先讓你確認後才建立");
    }

    private IntentResult result(String message) {
        return IntentResult.message(IntentResult.Action.TRAVEL_INFO, message);
    }

    private static boolean isSafeStandaloneRequest(String text) {
        if (text.isBlank() || text.length() > 220 || looksLikeHistoryQuestion(text)) {
            return false;
        }
        return !containsAny(text, "功能改善", "提出改善", "使用者", "對話情境", "例如你要",
                "你要能", "你就要", "應該要能", "需求功能", "開發功能");
    }

    private static boolean looksLikeHistoryQuestion(String text) {
        return containsAny(text, "上次", "之前", "曾經", "去過", "有去")
                && containsAny(text, "什麼時候", "何時", "嗎", "有沒有");
    }

    private static boolean looksLikeTrip(String text) {
        return isNearbyLeisure(text)
                || containsAny(text, "出去玩", "出遊", "旅行", "旅遊", "出國", "國外", "郵輪",
                "親子樂園", "親子出遊", "國內旅遊", "國內旅行")
                || (containsAny(text, "搭高鐵", "坐高鐵", "搭火車", "坐火車")
                && containsAny(text, "去", "玩"));
    }

    private static boolean isNearbyLeisure(String text) {
        return containsAny(text, "家附近", "住家附近", "附近公園", "住家周邊")
                && containsAny(text, "玩", "散步", "走走", "休閒", "公園");
    }

    private static boolean isInternational(String text, String destination) {
        return destination != null || containsAny(text, "出國", "國外", "郵輪", "飛機", "航班");
    }

    private static boolean isDomestic(String text) {
        return containsAny(text, "國內旅遊", "國內旅行", "搭高鐵", "坐高鐵", "搭火車", "坐火車");
    }

    private static boolean isFamilyOuting(String text) {
        return containsAny(text, "親子樂園", "親子出遊", "家庭出遊", "帶小孩出去玩", "帶孩子出去玩");
    }

    private static Optional<String> dateRange(String text) {
        Matcher matcher = DATE_RANGE.matcher(text);
        return matcher.find()
                ? Optional.of("%s/%s–%s/%s".formatted(
                        matcher.group(1), matcher.group(2), matcher.group(1), matcher.group(3)))
                : Optional.empty();
    }

    private static Optional<String> foreignDestination(String text) {
        return FOREIGN_DESTINATIONS.stream().filter(text::contains).findFirst();
    }

    private static Optional<String> transport(String text) {
        if (text.contains("郵輪")) return Optional.of("郵輪");
        if (containsAny(text, "飛機", "航班")) return Optional.of("飛機");
        if (text.contains("高鐵")) return Optional.of("高鐵");
        if (text.contains("火車")) return Optional.of("火車");
        if (text.contains("開車")) return Optional.of("開車");
        if (text.contains("騎車")) return Optional.of("騎車");
        return Optional.empty();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "")
                .replace('？', '?').toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
