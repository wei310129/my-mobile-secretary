package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteConversationOperationCompletionContributorTest {

    private CalendarIntentDraftService drafts;
    private RoutePlaceCreationDraftService placeChildren;
    private RouteConversationOperationCompletionContributor contributor;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        drafts = mock(CalendarIntentDraftService.class);
        placeChildren = mock(RoutePlaceCreationDraftService.class);
        contributor = new RouteConversationOperationCompletionContributor(
                drafts, placeChildren);
        workflowId = UUID.randomUUID();
        when(drafts.findAvailableForLifecycle(workflowId))
                .thenReturn(Optional.of(mock(CalendarIntentDraftService.DraftView.class)));
    }

    @Test
    void committedCalendarDataWithoutVerifiedRouteRemainsIncomplete() {
        when(drafts.hasDurableCompletedStandaloneRoute(workflowId)).thenReturn(false);

        assertThat(contributor.assess(workflowId).orElseThrow().status())
                .isEqualTo(ConversationOperationCompletionContributor.Status.INCOMPLETE);
    }

    @Test
    void verifiedRouteWithRequiredPlaceChildStillOpenRemainsIncomplete() {
        when(drafts.hasDurableCompletedStandaloneRoute(workflowId)).thenReturn(true);
        when(placeChildren.hasUnfinishedChild(workflowId)).thenReturn(true);

        assertThat(contributor.assess(workflowId).orElseThrow().status())
                .isEqualTo(ConversationOperationCompletionContributor.Status.INCOMPLETE);
    }

    @Test
    void onlyDurableVerifiedRouteWithoutRequiredChildIsComplete() {
        when(drafts.hasDurableCompletedStandaloneRoute(workflowId)).thenReturn(true);
        when(placeChildren.hasUnfinishedChild(workflowId)).thenReturn(false);

        assertThat(contributor.assess(workflowId).orElseThrow().status())
                .isEqualTo(ConversationOperationCompletionContributor.Status.COMPLETED);
    }
}
