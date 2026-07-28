package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;

/** The sole response carrier for a Java-rendered focus notice and a public reply. */
public record FocusResponseEnvelope(String message, FocusTransitionNotice notice) {

    public FocusResponseEnvelope {
        message = Objects.requireNonNull(message, "message");
    }

    public static FocusResponseEnvelope withoutNotice(String message) {
        return new FocusResponseEnvelope(message, null);
    }

    public static FocusResponseEnvelope withNotice(String message, FocusTransitionNotice notice,
                                                   ConversationFocusReplyDecorator decorator) {
        Objects.requireNonNull(notice, "notice");
        return new FocusResponseEnvelope(Objects.requireNonNull(decorator, "decorator")
                .decorate(message, notice), notice);
    }
}
