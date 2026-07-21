package com.aproject.aidriven.mymobilesecretary.conversation.application;

/** Domain-owned validator for a focus root; routing data alone can never authorize it. */
public interface ConversationFocusContributor {

    String rootDomain();

    boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target);
}
