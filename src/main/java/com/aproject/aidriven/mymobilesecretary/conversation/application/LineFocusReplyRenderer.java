package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;

/** LINE consumes the already-decorated envelope and must never render a second notice. */
public final class LineFocusReplyRenderer {

    public String render(FocusResponseEnvelope envelope) {
        return Objects.requireNonNull(envelope, "envelope").message();
    }
}
