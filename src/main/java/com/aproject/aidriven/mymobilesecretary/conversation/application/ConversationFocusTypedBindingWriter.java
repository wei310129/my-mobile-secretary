package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;

/**
 * Domain-owned persistence hook for workflow roots that require a real foreign-key binding.
 */
public interface ConversationFocusTypedBindingWriter {

    String rootDomain();

    void bind(ConversationFocus focus);
}
