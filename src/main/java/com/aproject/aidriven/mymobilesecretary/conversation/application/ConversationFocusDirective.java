package com.aproject.aidriven.mymobilesecretary.conversation.application;

/** Typed post-result focus instruction; terminal business state never implies a text-driven action. */
public enum ConversationFocusDirective {
    INVALIDATE_TARGET,
    /** Result is a conversation-only control and must never become a LifeRecord/tag fact. */
    FOCUS_CONTROL_ONLY
}
