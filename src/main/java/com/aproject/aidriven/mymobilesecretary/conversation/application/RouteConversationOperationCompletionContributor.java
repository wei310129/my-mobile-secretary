package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Route-only completion proof; later capabilities are enabled separately. */
@Component
public final class RouteConversationOperationCompletionContributor
        implements ConversationOperationCompletionContributor {

    public static final String KIND = "route";

    private final CalendarIntentDraftService drafts;
    private final RoutePlaceCreationDraftService placeChildren;

    public RouteConversationOperationCompletionContributor(
            CalendarIntentDraftService drafts,
            RoutePlaceCreationDraftService placeChildren) {
        this.drafts = drafts;
        this.placeChildren = placeChildren;
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public Optional<Assessment> assess(UUID workflowId) {
        if (workflowId == null
                || drafts.findAvailableForLifecycle(workflowId).isEmpty()) {
            return Optional.empty();
        }
        boolean complete = drafts.hasDurableCompletedStandaloneRoute(workflowId)
                && !placeChildren.hasUnfinishedChild(workflowId);
        return Optional.of(new Assessment(
                KIND,
                workflowId,
                complete ? Status.COMPLETED : Status.INCOMPLETE));
    }
}
