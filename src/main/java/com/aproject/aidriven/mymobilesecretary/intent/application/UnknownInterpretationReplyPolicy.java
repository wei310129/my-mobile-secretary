package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Set;

/** Maps UNKNOWN diagnostics to fixed public facts and one typed next question. */
final class UnknownInterpretationReplyPolicy {

    private static final String ADJACENT_GAP = "指定行程前後的相鄰空檔";
    private static final Set<String> TRUSTED_SCHEDULE_ANALYSIS_REASONS = Set.of(
            "這個複合查詢目前還不能完整處理：指定行程前後的相鄰空檔。"
                    + "這次只會回答已可靠辨識的查詢，不會建立或修改資料。",
            "指定行程前後的相鄰空檔查詢目前還不能完整處理。"
                    + "這次只會回答已可靠辨識的唯讀查詢，不會建立或修改資料。",
            "指定行程前後的相鄰空檔查詢目前還不能完整處理；"
                    + "同一句還有行程分析以外的其他要求，需要分開確認。"
                    + "這次只會回答已可靠辨識的唯讀查詢，不會建立或修改資料。");
    private static final String UNGROUNDED_OPERATION =
            "有一個操作無法對應到你這次的原話；確認前不會執行。";

    private UnknownInterpretationReplyPolicy() {
    }

    static IntentResult clarification(String reason) {
        return clarification(reason, null);
    }

    static IntentResult clarification(String reason, String previousAssistantText) {
        return clarification(reason, previousAssistantText, null);
    }

    static IntentResult clarification(
            String reason, String previousAssistantText, String previousQuestionCode) {
        Reply reply = classify(reason);
        if (isGeneric(reply) && previousQuestionCode != null
                && previousQuestionCode.startsWith("intent.unknown-")) {
            if ("intent.unknown-complete-request".equals(previousQuestionCode)) {
                return IntentResult.message(
                        IntentResult.Action.FAILURE_EXPLAINED,
                        "我仍無法把這段話安全對應到可執行的功能，因此這次不會建立或修改資料。"
                                + "請改用完整句子說明動作、對象與必要時間，或先查看功能說明。");
            }
            reply = nextRecoveryCode(previousQuestionCode);
        } else if (isGeneric(reply) && previousAssistantText != null) {
            reply = nextRecovery(previousAssistantText);
        }
        return IntentResult.clarificationNeeded(reply.fact(), ClarificationStep.blocking(
                reply.code(), "intent.action", reply.question(), 10));
    }

    static String message(String reason) {
        Reply reply = classify(reason);
        return reply.fact() + "\n\n" + reply.question();
    }

    private static Reply classify(String reason) {
        if (reason == null || reason.isBlank()) {
            return generic();
        }
        if (TRUSTED_SCHEDULE_ANALYSIS_REASONS.contains(reason)) {
            return new Reply(
                    "intent.unsupported-schedule-analysis",
                    "這個複合查詢目前還不能完整處理「%s」；"
                            .formatted(ADJACENT_GAP)
                            + "這次只會回答已可靠辨識的唯讀查詢，不會建立或修改資料。",
                    "你要我先處理哪一項？");
        }
        if (UNGROUNDED_OPERATION.equals(reason)) {
            return new Reply(
                    "intent.ungrounded-operation",
                    "有一個操作無法對應到你這次的原話，因此不會執行。",
                    "你要我執行哪一個具體動作？");
        }
        String compact = reason == null ? "" : reason.replaceAll("\\s+", "");
        if (IntentService.looksLikeInternalDiagnostic(reason, compact)) {
            return generic();
        }
        if (containsAny(compact,
                "使用者是在", "使用者已", "系統應", "無法對應到任何能力",
                "不是要建立", "目前無法直接判定", "才能執行")) {
            return new Reply(
                    "intent.context-target",
                    "我知道你是在追問上一則回覆，但我還沒有唯一對到你指的項目。",
                    "你指的是哪一個名稱或清單編號？");
        }
        return generic();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGeneric(Reply reply) {
        return "intent.unknown-action".equals(reply.code());
    }

    private static Reply nextRecovery(String previousAssistantText) {
        if (previousAssistantText.contains("先看今天安排")) {
            return new Reply(
                    "intent.unknown-operation-kind",
                    "我們仍然沒有唯一對到需求；目前不會建立或修改資料。",
                    "你要查看資料、建立資料，還是修改既有資料？");
        }
        if (previousAssistantText.contains("待辦、行程，還是提醒")) {
            return new Reply(
                    "intent.unknown-scope",
                    "我還沒有對到你要處理的範圍；目前不會建立或修改資料。",
                    "你要先看今天安排，還是處理某個指定項目？");
        }
        if (previousAssistantText.contains("查看資料、建立資料，還是修改既有資料")) {
            return new Reply(
                    "intent.unknown-target",
                    "我已經知道你要選擇操作類型，但還沒有唯一對到要處理的內容；目前不會建立或修改資料。",
                    "請用一個名稱告訴我要處理哪個項目？");
        }
        if (previousAssistantText.contains("要處理哪個項目")) {
            return new Reply(
                    "intent.unknown-complete-request",
                    "目前仍缺少能安全執行的完整要求，因此不會建立或修改資料。",
                    "請用一句話告訴我要做的動作和對象？");
        }
        return generic();
    }

    private static Reply nextRecoveryCode(String code) {
        return switch (code) {
            case "intent.unknown-action" -> new Reply(
                    "intent.unknown-scope",
                    "我還沒有對到你要處理的範圍；目前不會建立或修改資料。",
                    "你要先看今天安排，還是處理某個指定項目？");
            case "intent.unknown-scope" -> new Reply(
                    "intent.unknown-operation-kind",
                    "我們仍然沒有唯一對到需求；目前不會建立或修改資料。",
                    "你要查看資料、建立資料，還是修改既有資料？");
            case "intent.unknown-operation-kind" -> new Reply(
                    "intent.unknown-target",
                    "我已經知道你要選擇操作類型，但還沒有唯一對到要處理的內容；目前不會建立或修改資料。",
                    "請用一個名稱告訴我要處理哪個項目？");
            default -> new Reply(
                    "intent.unknown-complete-request",
                    "目前仍缺少能安全執行的完整要求，因此不會建立或修改資料。",
                    "請用一句話告訴我要做的動作和對象？");
        };
    }

    private static Reply generic() {
        return new Reply(
                "intent.unknown-action",
                "我目前無法判斷你要執行的動作，因此不會建立或修改資料。",
                "你要我先處理待辦、行程，還是提醒？");
    }

    private record Reply(String code, String fact, String question) {
    }
}
