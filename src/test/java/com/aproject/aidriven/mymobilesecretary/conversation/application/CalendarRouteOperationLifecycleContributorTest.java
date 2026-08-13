package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.RouteJourneyKind;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationStep;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRouteOperationLifecycleContributorTest {

    @Test
    void missingOriginResumesTheSameRouteWithoutClaimingItWasCreated() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        CalendarRouteOperationLifecycleContributor contributor =
                new CalendarRouteOperationLifecycleContributor(drafts);
        CalendarIntentDraftService.DraftView pending = route(null,
                CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED,
                CalendarIntentDraftService.Status.PENDING);
        when(drafts.findAvailableForLifecycle(pending.id())).thenReturn(Optional.of(pending));

        var operation = contributor.resolve(reference(pending.id())).orElseThrow();
        var question = contributor.resumeQuestion(operation, "route.origin").orElseThrow();

        assertThat(question.lifecycleContext().publicTopic()).isEqualTo("前往高鐵桃園站");
        assertThat(question.lifecycleContext().activeStep()).contains("出發地點");
        assertThat(question.lifecycleContext().preservedFacts()).contains("尚未建立行程");
        assertThat(question.prompt()).contains("行程尚未建立");
        assertThat(question.prompt()).doesNotContain("已建立");
    }

    @Test
    void closingPendingRouteIsOneTypedDomainMutationAndKeepsCommittedData() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        CalendarRouteOperationLifecycleContributor contributor =
                new CalendarRouteOperationLifecycleContributor(drafts);
        CalendarIntentDraftService.DraftView pending = route(location(),
                CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE,
                CalendarIntentDraftService.Status.PENDING);
        when(drafts.findAvailableForLifecycle(pending.id())).thenReturn(Optional.of(pending));
        when(drafts.discard(pending.id(), pending.revision())).thenReturn(true);

        var outcome = contributor.close(new ConversationOperationLifecycleContributor.Operation(
                CalendarRouteOperationLifecycleContributor.KIND,
                CalendarIntentDraftService.ROOT_DOMAIN,
                pending.id(),
                null,
                pending.title(),
                true));

        assertThat(outcome.domainMutationCount()).isEqualTo(1);
        assertThat(outcome.committedDataPreserved()).isTrue();
        verify(drafts).discard(pending.id(), pending.revision());
    }

    @Test
    void placeChildNamesItsParentAndResumesThatExactRoute() {
        RoutePlaceCreationDraftService children = mock(RoutePlaceCreationDraftService.class);
        CalendarIntentDraftService routes = mock(CalendarIntentDraftService.class);
        RoutePlaceCreationOperationLifecycleContributor contributor =
                new RoutePlaceCreationOperationLifecycleContributor(children, routes);
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        RoutePlaceCreationDraft child = mock(RoutePlaceCreationDraft.class);
        when(child.getId()).thenReturn(childId);
        when(child.getParentCalendarDraftId()).thenReturn(parentId);
        when(child.getRequestedAlias()).thenReturn("公司");
        when(child.getStep()).thenReturn(RoutePlaceCreationStep.CONFIRM);
        CalendarIntentDraftService.DraftView parent = route(null,
                CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED,
                CalendarIntentDraftService.Status.PENDING, parentId);
        when(children.findAvailableForLifecycle(childId)).thenReturn(Optional.of(child));
        when(routes.findAvailableForLifecycle(parentId)).thenReturn(Optional.of(parent));

        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                RoutePlaceCreationDraftService.ROOT_DOMAIN, childId, null, "公司")).orElseThrow();
        var parentOperation = contributor.parent(operation).orElseThrow();
        var question = contributor.resumeQuestion(operation, "place.route-create-confirm").orElseThrow();

        assertThat(parentOperation.workflowId()).isEqualTo(parentId);
        assertThat(parentOperation.safeLabel()).isEqualTo("前往高鐵桃園站");
        assertThat(question.lifecycleContext().publicTopic()).isEqualTo("前往高鐵桃園站");
        assertThat(question.prompt()).contains("回到原本的路線規劃");
        assertThat(question.prompt()).contains("行程尚未建立");
    }

    private static ConversationOperationLifecycleContributor.Reference reference(UUID draftId) {
        return new ConversationOperationLifecycleContributor.Reference(
                CalendarIntentDraftService.ROOT_DOMAIN, draftId, null, "前往高鐵桃園站");
    }

    private static CalendarIntentDraftService.DraftView route(
            CalendarLocation origin,
            CalendarIntentDraftService.RouteProviderStatus providerStatus,
            CalendarIntentDraftService.Status status) {
        return route(origin, providerStatus, status, UUID.randomUUID());
    }

    private static CalendarIntentDraftService.DraftView route(
            CalendarLocation origin,
            CalendarIntentDraftService.RouteProviderStatus providerStatus,
            CalendarIntentDraftService.Status status,
            UUID draftId) {
        Instant departure = Instant.parse("2026-08-13T01:00:00Z");
        return new CalendarIntentDraftService.DraftView(
                draftId,
                "前往高鐵桃園站",
                CalendarPlacement.point(departure, ZoneId.of("Asia/Taipei")),
                "交通",
                origin,
                new CalendarLocation("高鐵桃園站", 25.013, 121.215),
                origin == null ? null : CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                providerStatus,
                null,
                null,
                null,
                RouteJourneyKind.STANDALONE_TRIP,
                status,
                1,
                null,
                departure.plusSeconds(3600));
    }

    private static CalendarLocation location() {
        return new CalendarLocation("捷運大坪林站", 24.983, 121.541);
    }
}
