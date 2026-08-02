package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import org.springframework.stereotype.Component;

/** Revalidates an actor-private pending Calendar proposal before focus transitions. */
@Component
public final class CalendarDraftConversationFocusContributor
        implements ConversationFocusContributor {

    private final CalendarIntentDraftService drafts;

    public CalendarDraftConversationFocusContributor(CalendarIntentDraftService drafts) {
        this.drafts = drafts;
    }

    @Override
    public String rootDomain() {
        return CalendarIntentDraftConversationService.FOCUS_DOMAIN;
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        return target.workflowId() != null
                && drafts.isAvailableForFocus(target.workflowId());
    }
}
