package com.aproject.aidriven.mymobilesecretary.intent.application;

import org.springframework.stereotype.Component;

/** Maps typed internal diagnostics to fixed user-language facts without rendering diagnostic detail. */
@Component
public class ConversationErrorResponsePolicy {

    public PublicConversationReply publicReply(ConversationDiagnostic diagnostic) {
        return switch (diagnostic.code()) {
            case PRE_EXECUTION_FAILED -> PublicConversationReply.terminal(
                    "抱歉，剛才沒有處理完成，資料沒有異動。您可以稍後再傳一次。",
                    PublicConversationReply.TerminalState.FAILED,
                    PublicReplyEvidence.ZERO_MUTATION_VERIFIED);
            case PROCESSING_RESULT_UNKNOWN -> PublicConversationReply.terminal(
                    "剛才的處理結果目前還無法確認。為避免重複變更，我先不重做；"
                            + "您可以先查看目前資料。",
                    PublicConversationReply.TerminalState.FAILED,
                    PublicReplyEvidence.REPLAY_VERIFIED);
            case WORKSPACE_ROLE_DENIED -> PublicConversationReply.terminal(
                    "您目前可以查看這個工作區的資料，但還不能透過對話修改內容。",
                    PublicConversationReply.TerminalState.FAILED,
                    PublicReplyEvidence.ZERO_MUTATION_VERIFIED);
            case BUSINESS_RULE_REJECTED -> businessRuleReply(diagnostic.detail());
            case UNKNOWN_INTERPRETATION -> needsInput(
                    "我還沒有理解您希望我做什麼。您要我查詢、建立，還是調整資料？",
                    "intent.unknown-action");
        };
    }

    private static PublicConversationReply businessRuleReply(String code) {
        return switch (code == null ? "" : code) {
            case "MISSING_COORDINATES" -> needsInput(
                    "我還不能替您建立這個地點，因為目前無法確認位置。您可以提供地址嗎？",
                    "place.address");
            case "PLACE_NOT_FOUND_ON_GOOGLE" -> needsInput(
                    "我目前沒有找到這個地點。您可以提供更完整的店名、分店或地址嗎？",
                    "place.query");
            case "PLACE_LOOKUP_FAILED" -> PublicConversationReply.terminal(
                    "我現在暫時查不到這個地點，資料沒有異動。您可以稍後再試一次。",
                    PublicConversationReply.TerminalState.FAILED,
                    PublicReplyEvidence.ZERO_MUTATION_VERIFIED);
            default -> needsInput(
                    "這次沒有建立或修改資料。您可以換個方式說明要處理的內容嗎？",
                    "intent.clarification");
        };
    }

    private static PublicConversationReply needsInput(String message, String code) {
        return new PublicConversationReply(
                message,
                PublicConversationReply.TerminalState.NEEDS_INPUT,
                new PublicConversationReply.NextQuestion(code, lastQuestion(message)),
                java.util.Set.of(PublicReplyEvidence.ZERO_MUTATION_VERIFIED));
    }

    private static String lastQuestion(String message) {
        int start = Math.max(message.lastIndexOf('。'), message.lastIndexOf('；')) + 1;
        return message.substring(start).strip();
    }
}
