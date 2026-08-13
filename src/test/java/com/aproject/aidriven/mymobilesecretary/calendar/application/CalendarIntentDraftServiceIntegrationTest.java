package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.application.RoutePlaceCreationDraftService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.ResolvedPlaceCandidate;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarIntentDraftServiceIntegrationTest extends IntegrationTestBase {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Instant DEPARTURE = Instant.parse("2026-08-13T01:00:00Z");

    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private RoutePlaceCreationDraftService placeChildren;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void providerFailureCreatesNoCalendarPlanAndCannotBeDescribedAsCreated() {
        inTenant(() -> {
            CalendarIntentDraftService.DraftView draft = drafts.startRoute(route("provider-failure"));
            CalendarIntentDraftService.DraftView failed = drafts.recordProviderFailure(
                    draft.id(),
                    draft.revision(),
                    new CalendarIntentDraftService.RouteProviderFailure(
                            CalendarIntentDraftService.RouteTravelMode.TRANSIT,
                            CalendarIntentDraftService.RouteTimeRole.DEPART_AT));

            assertThat(failed.providerStatus())
                    .isEqualTo(CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE);
            assertThat(failed.mayClaimCreated()).isFalse();
            assertThat(calendarPlanCount()).isZero();
        });
    }

    @Test
    void successfulEvidenceMaterializesExactlyOneCalendarPlanAndReplayAddsNone() {
        inTenant(() -> {
            CalendarIntentDraftService.DraftView draft = drafts.startRoute(route("provider-success"));
            CalendarIntentDraftService.DraftView evidenced = drafts.recordStandaloneRouteEvidence(
                    draft.id(),
                    draft.revision(),
                    evidence());

            assertThat(evidenced.mayClaimCreated()).isFalse();
            assertThat(calendarPlanCount()).isZero();

            CalendarIntentDraftService.MaterializationResult first = drafts.materializeStandaloneRoute(
                    evidenced.id(), evidenced.revision());
            CalendarIntentDraftService.MaterializationResult replay = drafts.materializeStandaloneRoute(
                    evidenced.id(), evidenced.revision());

            assertThat(first.replayed()).isFalse();
            assertThat(first.draft().mayClaimCreated()).isTrue();
            assertThat(first.plan()).isNotNull();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.plan()).isNull();
            assertThat(calendarPlanCount()).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM calendar_time_node WHERE plan_id = ?",
                    Long.class,
                    first.plan().planId())).isEqualTo(2L);
            assertThat(jdbc.queryForList(
                    "SELECT location_label FROM calendar_time_node WHERE plan_id = ? ORDER BY node_key",
                    String.class,
                    first.plan().planId()))
                    .containsExactly("高鐵桃園站", "捷運大坪林站");
        });
    }

    @Test
    void placeChildCompletesOnlyTheChildStepThenReturnsToPendingRoute() {
        inTenant(() -> {
            CalendarIntentDraftService.DraftView draft = drafts.startRoute(routeWithoutOrigin("place-child"));
            var child = placeChildren.startCandidate(
                    draft.id(),
                    draft.revision(),
                    "公司",
                    "公司地址",
                    new ResolvedPlaceCandidate("公司", "公司地址", 25.032, 121.565, "office"));

            RoutePlaceCreationDraftService.Completion completion =
                    placeChildren.completeForThisRoute(child.getId());
            RoutePlaceCreationDraftService.Completion replay =
                    placeChildren.completeForThisRoute(child.getId());

            assertThat(completion.replayed()).isFalse();
            assertThat(completion.parent().origin().label()).isEqualTo("公司");
            assertThat(completion.parent().status())
                    .isEqualTo(CalendarIntentDraftService.Status.PENDING);
            assertThat(completion.parent().mayClaimCreated()).isFalse();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.parent().status())
                    .isEqualTo(CalendarIntentDraftService.Status.PENDING);
            assertThat(calendarPlanCount()).isZero();
        });
    }

    @Test
    void pendingPlaceChildBlocksCalendarMaterializationUntilParentCanResume() {
        inTenant(() -> {
            CalendarIntentDraftService.DraftView draft = drafts.startRoute(route("child-block"));
            CalendarIntentDraftService.DraftView evidenced = drafts.recordStandaloneRouteEvidence(
                    draft.id(), draft.revision(), evidence());
            placeChildren.startCandidate(
                    evidenced.id(),
                    evidenced.revision(),
                    "公司",
                    "公司地址",
                    new ResolvedPlaceCandidate("公司", "公司地址", 25.032, 121.565, "office"));

            assertThatThrownBy(() -> drafts.materializeStandaloneRoute(
                    evidenced.id(), evidenced.revision()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageNotContaining("route_place_creation_draft")
                    .hasMessageNotContaining("UUID");
            assertThat(calendarPlanCount()).isZero();
        });
    }

    @Test
    void lifecycleLookupCannotCrossConversationScope() {
        WorkspaceContext first = tenant("scope-one");
        WorkspaceContext second = tenant("scope-two");
        CalendarIntentDraftService.DraftView draft = inContext(first,
                () -> drafts.startRoute(route("scope-bound")));

        assertThat(inContext(second, () -> drafts.findAvailableForLifecycle(draft.id()))).isEmpty();
        assertThat(inContext(first, () -> drafts.findAvailableForLifecycle(draft.id()))).isPresent();
    }

    @Test
    void routeAndPlaceChildAreInvisibleAcrossActorAndWorkspace() {
        UUID owner = UUID.randomUUID();
        UUID ownerWorkspace = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID outsiderWorkspace = UUID.randomUUID();
        seedTenant(owner, ownerWorkspace, "route owner");
        seedTenant(outsider, outsiderWorkspace, "route outsider");
        WorkspaceContext ownerContext = new WorkspaceContext(
                owner, ownerWorkspace, WorkspaceChannel.TEST, "calendar-route-test", "owner-scope");
        WorkspaceContext outsiderContext = new WorkspaceContext(
                outsider, outsiderWorkspace, WorkspaceChannel.TEST, "calendar-route-test", "outsider-scope");
        CalendarIntentDraftService.DraftView draft = inContext(ownerContext,
                () -> drafts.startRoute(routeWithoutOrigin("actor-bound")));
        var child = inContext(ownerContext, () -> placeChildren.startCandidate(
                draft.id(),
                draft.revision(),
                "公司",
                "公司地址",
                new ResolvedPlaceCandidate("公司", "公司地址", 25.032, 121.565, "office")));

        assertThat(inContext(outsiderContext, () -> drafts.findAvailableForLifecycle(draft.id())))
                .isEmpty();
        assertThat(inContext(outsiderContext, () -> placeChildren.findAvailableForLifecycle(child.getId())))
                .isEmpty();
        assertThat(inContext(ownerContext, () -> drafts.findAvailableForLifecycle(draft.id())))
                .isPresent();
        assertThat(inContext(ownerContext, () -> placeChildren.findAvailableForLifecycle(child.getId())))
                .isPresent();
    }

    private static CalendarIntentDraftService.RouteDraftRequest route(String requestKey) {
        return route(requestKey, new CalendarLocation("捷運大坪林站", 24.983, 121.541));
    }

    private static CalendarIntentDraftService.RouteDraftRequest routeWithoutOrigin(String requestKey) {
        return route(requestKey, null);
    }

    private static CalendarIntentDraftService.RouteDraftRequest route(
            String requestKey, CalendarLocation origin) {
        return new CalendarIntentDraftService.RouteDraftRequest(
                requestKey,
                "前往高鐵桃園站",
                CalendarPlacement.point(DEPARTURE, TAIPEI),
                "交通",
                origin,
                origin == null ? null : CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                new CalendarLocation("高鐵桃園站", 25.013, 121.215),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN,
                RouteJourneyKindPolicy.SourceSemantics.EXPLICIT_STANDALONE_TRIP);
    }

    private static CalendarIntentDraftService.RouteProviderEvidence evidence() {
        return new CalendarIntentDraftService.RouteProviderEvidence(
                CalendarIntentDraftService.RouteProvider.TDX,
                CalendarIntentDraftService.RouteTravelMode.TRANSIT,
                CalendarIntentDraftService.RouteTimeRole.DEPART_AT,
                DEPARTURE.minusSeconds(60),
                DEPARTURE,
                DEPARTURE.plusSeconds(42 * 60));
    }

    private long calendarPlanCount() {
        return jdbc.queryForObject("SELECT count(*) FROM calendar_plan", Long.class);
    }

    private void inTenant(Runnable work) {
        inContext(tenant("route-test"), () -> {
            work.run();
            return null;
        });
    }

    private static WorkspaceContext tenant(String scope) {
        return new WorkspaceContext(
                LegacyAccountIds.USER_ID,
                LegacyAccountIds.WORKSPACE_ID,
                WorkspaceChannel.TEST,
                "calendar-route-test",
                scope + "-" + UUID.randomUUID());
    }

    private static <T> T inContext(WorkspaceContext context, java.util.function.Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void seedTenant(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }
}
