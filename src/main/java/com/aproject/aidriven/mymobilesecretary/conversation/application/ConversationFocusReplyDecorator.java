package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Adds exactly one Java-rendered notice after a public reply when a transition occurred. */
@Component
public final class ConversationFocusReplyDecorator {

    private final FocusTransitionNoticeRenderer renderer;

    public ConversationFocusReplyDecorator(FocusTransitionNoticeRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public String decorate(String publicReply, FocusTransitionNotice notice) {
        String reply = Objects.requireNonNull(publicReply, "publicReply");
        return notice == null ? reply : reply + "\n\n" + renderer.render(notice);
    }
}
