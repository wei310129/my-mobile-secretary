package com.aproject.aidriven.mymobilesecretary.travel.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Provides one scope-local workflow identity for the current travel-planning conversation. */
@Component
public final class TravelConversationFocusBindingFactory {

    private final ConversationScopeResolver scopeResolver;

    public TravelConversationFocusBindingFactory(ConversationScopeResolver scopeResolver) {
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
    }

    public ConversationFocusBinding planning() {
        return ConversationFocusBinding.workflow("TRAVEL", workflowId(), "這趟旅行規劃");
    }

    public ConversationFocusBinding packing() {
        return ConversationFocusBinding.workflowActivity(
                "TRAVEL", workflowId(), "這趟旅行規劃", "PACKING", "行李準備");
    }

    private UUID workflowId() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        String scopeDigest = scopeResolver.current(context).digest();
        return UUID.nameUUIDFromBytes(
                ("travel-planning:" + scopeDigest).getBytes(StandardCharsets.UTF_8));
    }
}
