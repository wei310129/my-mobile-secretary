package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;
import java.util.Optional;

/**
 * Keeps product requirements and correction feedback out of deterministic business handlers.
 *
 * <p>This check intentionally runs before family, task, and schedule shortcuts. The rules are
 * conservative: ordinary personal commands still flow to their domain handler, while explicit
 * development requests and long, generalized product rules are recorded as feedback without
 * mutating business data or spending an AI request.
 */
final class ProductFeedbackBoundary {

    private static final List<String> EXPLICIT_PRODUCT_MARKERS = List.of(
            "這是一個開發需求",
            "這是開發需求",
            "這是一個功能需求",
            "這是功能需求",
            "功能改善",
            "功能建議",
            "開發功能",
            "你的回應要調整",
            "系統應該要",
            "秘書應該要");

    private static final List<String> CORRECTION_MESSAGES = List.of(
            "你沒有聽懂",
            "你沒聽懂",
            "你理解錯了",
            "你搞錯了",
            "你誤會了",
            "你答非所問",
            "這不是我要的");

    private static final List<String> GENERALIZED_SUBJECTS = List.of(
            "使用者", "每個人", "未來", "一般也", "各種情況");

    private static final List<String> PRODUCT_RULES = List.of(
            "你要提醒",
            "要先和使用者確認",
            "要和使用者確認",
            "要有個優先順序",
            "應該要",
            "必須",
            "不得");

    private static final List<String> EXPLANATION_MARKERS = List.of(
            "例如", "比方", "好比", "前提", "也就是", "這比較不算");

    private ProductFeedbackBoundary() {
    }

    static Optional<IntentResult> answer(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String compact = text.replaceAll("\\s+", "")
                .replaceAll("[，。！？!?]+$", "");
        if (CORRECTION_MESSAGES.contains(compact) || isResponseCorrection(compact)) {
            return Optional.of(IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "🛠️ 收到，是我理解錯了。\n\n❓ 請直接告訴我原本要我做什麼，"
                            + "我會把這次誤判一併留給功能改善追蹤。"));
        }
        if (containsAny(compact, EXPLICIT_PRODUCT_MARKERS)
                || isGeneralizedProductRule(text, compact)) {
            return Optional.of(IntentResult.feedbackReceived());
        }
        return Optional.empty();
    }

    private static boolean isResponseCorrection(String compact) {
        boolean beginsAsCorrection = CORRECTION_MESSAGES.stream().anyMatch(compact::startsWith)
                || compact.startsWith("你完全都沒聽懂");
        return beginsAsCorrection && containsAny(compact, List.of(
                "你再跟我講", "你卻", "你的回應", "我在回應你", "答成", "草稿"));
    }

    private static boolean isGeneralizedProductRule(String text, String compact) {
        return text.length() >= 120
                && containsAny(compact, GENERALIZED_SUBJECTS)
                && containsAny(compact, PRODUCT_RULES)
                && containsAny(compact, EXPLANATION_MARKERS);
    }

    private static boolean containsAny(String text, List<String> markers) {
        return markers.stream().anyMatch(text::contains);
    }
}
