package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.application.RouteOperationPreferenceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteConversationOperationLifecycleContributorTest {

    @Test
    void currentUnfinishedFindsTheUniquePendingRouteWithoutTrustingTheBrokenPointer() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.PENDING);
        when(drafts.currentTransportConversation()).thenReturn(Optional.of(draft));
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts,
                        mock(RouteOperationPreferenceService.class),
                        mock(CalendarIntentDraftConversationService.class));

        assertThat(contributor.findCurrentUnfinished())
                .hasValueSatisfying(operation -> {
                    assertThat(operation.operationKind()).isEqualTo("route");
                    assertThat(operation.workflowId()).isEqualTo(id);
                    assertThat(operation.unfinishedDomainState()).isTrue();
                });
    }

    @Test
    void exactTypedDraftOverridesLegacyPendingDomainAndMissingLabel() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.PENDING);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));

        assertThat(contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "task", id, null, null)))
                .hasValueSatisfying(operation -> {
                    assertThat(operation.rootDomain()).isEqualTo("CALENDAR_DRAFT");
                    assertThat(operation.safeLabel()).isEqualTo("從捷運大坪林到高鐵桃園站");
                    assertThat(operation.unfinishedDomainState()).isTrue();
                });
    }

    @Test
    void exactStandaloneJourneyKindResolvesLegacyDraftBeforeTimeRoleWasPersisted() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.PENDING);
        when(draft.routeTimeRole()).thenReturn(null);
        when(draft.hasRouteJourneyKind()).thenReturn(true);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts,
                        mock(RouteOperationPreferenceService.class),
                        mock(CalendarIntentDraftConversationService.class));

        assertThat(contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "calendar_draft", id, null, "route.origin-context")))
                .hasValueSatisfying(operation -> {
                    assertThat(operation.workflowId()).isEqualTo(id);
                    assertThat(operation.unfinishedDomainState()).isTrue();
                });
    }

    @Test
    void closeDiscardsOnlyTheExactPendingRouteDraft() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.PENDING);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                "calendar_draft", id, null, null)).orElseThrow();

        assertThat(contributor.close(operation).domainMutationCount()).isEqualTo(1);
        verify(drafts).discard(id, 7L);
    }

    @Test
    void closeMaterializedRoutePreservesCommittedCalendarPlan() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.MATERIALIZED);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "calendar_draft", id, null, null))
                .orElseThrow();

        assertThat(contributor.close(operation))
                .satisfies(outcome -> {
                    assertThat(outcome.domainMutationCount()).isZero();
                    assertThat(outcome.committedDataPreserved()).isTrue();
                });
        verify(drafts, never()).discard(id, 7L);
    }

    @Test
    void resumeReplaysTheExactRouteQuestionInsteadOfOnlyNamingTheOperation() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.PENDING);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "task", id, null, null))
                .orElseThrow();

        assertThat(contributor.resumeQuestion(operation, "route.general-buffer"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicTopic()).isEqualTo(draft.title());
                    assertThat(question.publicProgress())
                            .isEqualTo("正在設定這趟路線的前後緩衝時間。");
                    assertThat(question.code()).isEqualTo("route.general-buffer");
                    assertThat(question.prompt()).contains("前10、後15");
                });
    }

    @Test
    void resumePreservesTheChosenConnectionBufferContinuation() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.MATERIALIZED);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "task", id, null, null))
                .orElseThrow();

        assertThat(contributor.resumeQuestion(operation, "route.connection-buffer-adjust"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicTopic()).isEqualTo(draft.title());
                    assertThat(question.publicProgress()).contains("往後安排", "前後緩衝");
                    assertThat(question.code()).isEqualTo("route.connection-buffer-adjust");
                    assertThat(question.prompt()).contains("前10分鐘", "後15分鐘");
                });
        assertThat(contributor.resumeQuestion(operation, "route.connection-buffer-keep"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicProgress()).contains("保留原安排", "前後緩衝");
                    assertThat(question.code()).isEqualTo("route.connection-buffer-keep");
                });
    }

    @Test
    void resumeRestoresTheExactConflictDecisionContext() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.MATERIALIZED);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts,
                        mock(RouteOperationPreferenceService.class),
                        mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "task", id, null, null))
                .orElseThrow();

        assertThat(contributor.resumeQuestion(operation, "route.schedule-conflict-keep-only"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicProgress()).contains("後方銜接", "無法解決");
                    assertThat(question.prompt()).contains("保留原安排");
                });
        assertThat(contributor.resumeQuestion(operation, "route.direct-overlap"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicProgress()).contains("直接重疊", "尚未建立");
                    assertThat(question.prompt()).contains("新的出發時間");
                    assertThat(question.choiceQuestion()).isNotNull();
                    assertThat(question.choiceQuestion().resolveAction("2"))
                            .contains("KEEP_ORIGINAL");
                });
    }

    @Test
    void resumeRecoversTheNextTypedRouteQuestionAfterGenericUnknownOverwrite() {
        CalendarIntentDraftService drafts = mock(CalendarIntentDraftService.class);
        RouteOperationPreferenceService preferences = mock(RouteOperationPreferenceService.class);
        UUID id = UUID.randomUUID();
        DraftView draft = routeDraft(id, Status.MATERIALIZED);
        when(draft.routeProviderStatus())
                .thenReturn(CalendarIntentDraftService.RouteProviderStatus.AVAILABLE);
        when(drafts.findAvailableForLifecycle(id)).thenReturn(Optional.of(draft));
        when(preferences.current()).thenReturn(Optional.empty());
        RouteConversationOperationLifecycleContributor contributor =
                new RouteConversationOperationLifecycleContributor(
                        drafts, preferences, mock(CalendarIntentDraftConversationService.class));
        var operation = contributor.resolve(new ConversationOperationLifecycleContributor.Reference(
                        "task", id, null, null))
                .orElseThrow();

        assertThat(contributor.resumeQuestion(operation, "intent.unknown-action"))
                .hasValueSatisfying(question -> {
                    assertThat(question.publicTopic()).isEqualTo(draft.title());
                    assertThat(question.publicProgress()).contains("出發提醒");
                    assertThat(question.code()).isEqualTo("route.departure-reminder");
                    assertThat(question.prompt()).contains("出發提醒");
                });
    }

    private static DraftView routeDraft(UUID id, Status status) {
        DraftView draft = mock(DraftView.class);
        when(draft.id()).thenReturn(id);
        when(draft.title()).thenReturn("從捷運大坪林到高鐵桃園站");
        when(draft.status()).thenReturn(status);
        when(draft.revision()).thenReturn(7L);
        when(draft.transportOrigin()).thenReturn(mock(
                com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation.class));
        when(draft.location()).thenReturn(mock(
                com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation.class));
        when(draft.transportMode()).thenReturn("TRANSIT");
        when(draft.routeProviderStatus())
                .thenReturn(CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED);
        when(draft.transportOfferStatus())
                .thenReturn(CalendarIntentDraftService.TransportOfferStatus.NONE);
        when(draft.routeTimeRole()).thenReturn("DEPART_AT");
        return draft;
    }
}
