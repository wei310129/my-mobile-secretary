package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a trusted quoted focus identifier without allowing quoted text to select a new action. */
@Service
public class ConversationFocusQuoteResolver {

    private final ConversationScopeResolver resolver;
    private final ConversationFocusRepository focuses;

    public ConversationFocusQuoteResolver(ConversationScopeResolver resolver,
                                         ConversationFocusRepository focuses) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.focuses = Objects.requireNonNull(focuses, "focuses");
    }

    @Transactional(readOnly = true)
    public QuotedFocusResolution resolveSuspendedFocus(UUID quotedFocusId) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        ConversationFocus focus = focuses
                .findByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(quotedFocusId,
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                .filter(candidate -> candidate.getStatus() == ConversationFocusStatus.SUSPENDED)
                .orElseThrow(() -> new IllegalStateException("quoted focus is unavailable"));
        return new QuotedFocusResolution(FocusDecision.transition(FocusTransitionType.RESUME),
                new FocusControl.Resume(focus.getId()), FocusTransitionNotice.forTransition(
                        FocusTransitionType.RESUME, null, focus.getSafeLabel(), null));
    }

    public record QuotedFocusResolution(FocusDecision.Transition decision, FocusControl.Resume control,
                                        FocusTransitionNotice notice) { }
}
