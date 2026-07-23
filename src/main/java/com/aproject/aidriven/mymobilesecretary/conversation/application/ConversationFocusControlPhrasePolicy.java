package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.Locale;
import java.util.Optional;

/** Bounded Java recognition for explicit conversation-focus controls. */
public final class ConversationFocusControlPhrasePolicy {

    private ConversationFocusControlPhrasePolicy() {
    }

    public static Optional<IntentCommand.Type> classify(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank() || !hasConversationTarget(normalized)) {
            return Optional.empty();
        }
        if (containsAny(normalized, "不用再承接", "不要再承接", "不再承接", "不用再接續",
                "不要再接續", "永久關閉")
                || (containsAny(normalized, "結束", "關閉", "關掉")
                && containsAny(normalized, "議題", "話題", "對話", "焦點"))) {
            return Optional.of(IntentCommand.Type.CLOSE_CONVERSATION_FOCUS);
        }
        if (containsAny(normalized, "離開", "跳出", "先擱著", "先放一邊", "暫停處理",
                "暫停承接")) {
            return Optional.of(IntentCommand.Type.EXIT_CONVERSATION_FOCUS);
        }
        return Optional.empty();
    }

    private static boolean hasConversationTarget(String text) {
        return containsAny(text, "這件事", "這個話題", "這段話題", "這個議題", "這段議題",
                "這段對話", "目前話題", "現在話題", "目前焦點", "現在焦點", "對話焦點",
                "正在處理的事", "現在處理的事", "目前處理的事");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？；;：:、]+", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
