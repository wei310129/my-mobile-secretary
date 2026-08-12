package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationVoiceProfileService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FeedbackPolarity;
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
            "開發指示",
            "這是我的開發指示",
            "要再開發這項能力",
            "要好好改進",
            "我認為你要",
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
            "這不是我要的",
            "完全不知所云",
            "你的格式不對",
            "格式不對");

    private static final List<String> PRAISE_MARKERS = List.of(
            "做得好", "做得很好", "做得不錯", "回答得好", "回答很好", "處理得好",
            "表現很好", "很棒", "真棒", "厲害", "太好了", "辛苦了");

    private static final List<String> DISSATISFACTION_MARKERS = List.of(
            "做得很差", "做得差", "回答很差", "處理得很差", "表現很差", "很不滿意",
            "不滿意", "很失望", "太失望", "很糟", "太糟", "很爛", "太爛");

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
        if (feedbackPolarity(text).filter(value -> value == FeedbackPolarity.PRAISE).isPresent()) {
            return Optional.of(IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    praiseReply(compact)));
        }
        if (deniesDuplicateDiagnosis(compact) && complainsAboutAnswer(compact)) {
            return Optional.of(IntentResult.feedbackNeedsInput(
                    "收到，是我理解錯了；您不是在說重複建立，而是在指出我的回答有誤。"
                            + "這則回饋本身不會建立或修改資料，目前也還沒有重新處理。",
                    ClarificationStep.blocking("feedback.wrong-answer-target", "repair.target",
                            "您希望我先釐清剛才回答的哪一部分？", 10)));
        }
        if (complainsAboutCrossDomainRelationship(compact)) {
            return Optional.of(IntentResult.feedbackNeedsInput(
                    "你指出得對：我剛才把待辦與行程分開回答，漏了兩者的時間關聯。"
                            + "這則回饋不會建立或修改任何資料。",
                    ClarificationStep.blocking("feedback.relationship-target", "repair.target",
                            "您希望我先確認待辦、行程，還是兩者的時間關聯？", 10)));
        }
        if (CORRECTION_MESSAGES.contains(compact) || isResponseCorrection(compact)) {
            return Optional.of(IntentResult.feedbackNeedsInput(
                    "收到，剛才是我理解錯了。這則回饋不會建立或修改資料，目前也還沒有重新處理。",
                    ClarificationStep.blocking("feedback.correction-target", "repair.target",
                            "您希望我先更正哪一部分？", 10)));
        }
        if (containsAny(compact, DISSATISFACTION_MARKERS)) {
            return Optional.of(IntentResult.feedbackNeedsInput(
                    "收到，這次沒有符合您的期待。",
                    ClarificationStep.blocking("feedback.dissatisfaction-focus", "repair.target",
                            "您最希望我先釐清哪一點？", 10)));
        }
        if (containsAny(compact, EXPLICIT_PRODUCT_MARKERS)
                || isGeneralizedProductRule(text, compact)) {
            return Optional.of(IntentResult.feedbackReceived());
        }
        return Optional.empty();
    }

    static Optional<ConversationRepairType> repairType(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String compact = text.replaceAll("\\s+", "")
                .replaceAll("[，。！？!?]+$", "");
        if (containsAny(compact, DISSATISFACTION_MARKERS)) {
            return Optional.of(ConversationRepairType.DISSATISFACTION);
        }
        if (complainsAboutCrossDomainRelationship(compact)) {
            return Optional.of(ConversationRepairType.CROSS_DOMAIN_RELATIONSHIP);
        }
        if (containsAny(compact, List.of("還在問", "又問", "再問", "重複問", "已經確認"))) {
            return Optional.of(ConversationRepairType.REPEATED_QUESTION);
        }
        if (containsAny(compact, List.of("格式不對", "格式有錯", "空行", "條列"))
                && isResponseCorrection(compact)) {
            return Optional.of(ConversationRepairType.FORMAT);
        }
        if (deniesDuplicateDiagnosis(compact) && complainsAboutAnswer(compact)
                || CORRECTION_MESSAGES.contains(compact) || isResponseCorrection(compact)) {
            return Optional.of(ConversationRepairType.WRONG_ANSWER);
        }
        if (containsAny(compact, EXPLICIT_PRODUCT_MARKERS)
                || isGeneralizedProductRule(text, compact)) {
            return Optional.of(ConversationRepairType.PRODUCT_RULE);
        }
        return Optional.empty();
    }

    static String correctionAcknowledgement(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "")
                .replaceAll("[，。！？!?]+$", "");
        if (deniesDuplicateDiagnosis(compact) && complainsAboutAnswer(compact)) {
            return "收到，剛才是我理解錯了；您不是在說重複建立，而是在指出我的回答有誤。"
                    + "這則回饋不會建立或修改資料，也不會說成已經修好。";
        }
        return "收到，剛才是我理解錯了。這則回饋不會建立或修改資料，也不會說成已經修好。";
    }

    private static String praiseReply(String compact) {
        int index = Math.floorMod(compact.hashCode(), SecretaryFeedbackVariantPolicy.VARIANT_COUNT);
        return SecretaryFeedbackVariantPolicy.praise(
                index, ConversationVoiceProfileService.Settings.defaults());
    }

    static Optional<FeedbackPolarity> feedbackPolarity(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String compact = text.replaceAll("\\s+", "")
                .replaceAll("[，。！？!?]+$", "");
        if (containsAny(compact, DISSATISFACTION_MARKERS)) {
            return Optional.of(FeedbackPolarity.DISSATISFACTION);
        }
        if (containsAny(compact, PRAISE_MARKERS) && !complainsAboutAnswer(compact)) {
            return Optional.of(FeedbackPolarity.PRAISE);
        }
        return Optional.empty();
    }

    private static boolean deniesDuplicateDiagnosis(String compact) {
        return containsAny(compact, List.of(
                "不是重複", "並不是重複", "不是在說重複", "並非重複",
                "不是在說你重複", "並非在說重複", "並非重複項目"));
    }

    private static boolean complainsAboutAnswer(String compact) {
        return containsAny(compact, List.of(
                "亂回答", "亂回", "答錯", "理解錯", "誤解", "答非所問",
                "憑空回覆", "憑空回答", "不該回覆", "不應該回覆"));
    }

    private static boolean complainsAboutCrossDomainRelationship(String compact) {
        boolean hasBothDomains = containsAny(compact, List.of("待辦", "任務"))
                && containsAny(compact, List.of("行程", "日曆", "時間"));
        boolean reportsMissingRelationship = containsAny(compact, List.of(
                "關聯", "連結", "串聯", "一起看", "整合", "分開", "沒有把", "沒把"));
        boolean reportsWrongAnswer = containsAny(compact, List.of(
                "回答錯", "答錯", "錯重點", "理解錯", "沒聽懂", "沒有聽懂", "漏了", "遺漏"));
        return hasBothDomains && reportsMissingRelationship && reportsWrongAnswer;
    }

    private static boolean isResponseCorrection(String compact) {
        boolean beginsAsCorrection = CORRECTION_MESSAGES.stream().anyMatch(compact::startsWith)
                || compact.startsWith("你完全都沒聽懂")
                || compact.startsWith("首先你的格式不對")
                || compact.startsWith("你把你的邏輯")
                || compact.startsWith("為什麼你明明");
        boolean structuredCorrection = beginsAsCorrection && containsAny(compact, List.of(
                "你再跟我講", "你卻", "你的回應", "我在回應你", "答成", "草稿",
                "回給使用者", "直接回", "還在問", "再問", "已經確認", "空行", "項次",
                "回答方式", "回覆方式", "答覆方式"));
        boolean referencesResponse = containsAny(compact, List.of(
                "上一則回答", "上一個回答", "剛才的回答", "剛剛的回答",
                "你的回答", "你的回覆", "你的回應", "你又回", "和上一則"));
        boolean reportsResponseFailure = containsAny(compact, List.of(
                "有錯", "錯了", "答錯", "無關", "不相關", "不對",
                "不應該", "不是要", "不要建立", "不該"));
        return structuredCorrection || (referencesResponse && reportsResponseFailure);
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
