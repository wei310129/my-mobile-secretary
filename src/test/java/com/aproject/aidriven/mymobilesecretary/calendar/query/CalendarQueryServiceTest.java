package com.aproject.aidriven.mymobilesecretary.calendar.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarQueryServiceTest extends IntegrationTestBase {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired private CalendarApplicationService calendar;
    @Autowired private CalendarQueryService queries;
    @Autowired private CalendarShareService shares;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void dateRangeReturnsOverlappingTypedPlacementsInDeterministicOrderWithoutLegacyMerge() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "查詢者");
        WorkspaceContext context = context(actor, workspace);
        inContext(context, () -> {
            create(
                    "range-interval",
                    "逛裕隆城",
                    CalendarPlacement.interval(
                            Instant.parse("2026-07-25T03:00:00Z"),
                            Instant.parse("2026-07-25T08:00:00Z"),
                            TAIPEI),
                    "休閒");
            create(
                    "range-point",
                    "電影",
                    CalendarPlacement.point(
                            Instant.parse("2026-07-25T03:30:00Z"), TAIPEI),
                    "娛樂");
            create(
                    "range-all-day",
                    "旅行日",
                    CalendarPlacement.allDay(
                            LocalDate.parse("2026-07-25"),
                            LocalDate.parse("2026-07-26")),
                    "旅行");
            return null;
        });

        CalendarQueryPage page = inContext(context, () -> queries.query(
                CalendarQueryFilter.range(
                        Instant.parse("2026-07-24T16:00:00Z"),
                        Instant.parse("2026-07-25T16:00:00Z"),
                        TAIPEI,
                        20,
                        0)));

        assertThat(page.items())
                .extracting(CalendarQueryItem::title)
                .containsExactly("旅行日", "逛裕隆城", "電影");
        assertThat(page.nextOffset()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_item", Long.class))
                .isZero();
    }

    @Test
    void keywordCategoryPaginationAndActorBoundaryAreEnforcedByJavaQueryPath() {
        UUID actor = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "搜尋者");
        seedUser(peer, "同工作區其他人");
        WorkspaceContext ownerContext = context(actor, workspace);
        inContext(ownerContext, () -> {
            create(
                    "search-a",
                    "台北電影",
                    CalendarPlacement.point(
                            Instant.parse("2026-07-25T04:00:00Z"), TAIPEI),
                    "娛樂");
            create(
                    "search-b",
                    "電影聚餐",
                    CalendarPlacement.point(
                            Instant.parse("2026-07-26T04:00:00Z"), TAIPEI),
                    "娛樂");
            create(
                    "search-c",
                    "親子電影",
                    CalendarPlacement.point(
                            Instant.parse("2026-07-27T04:00:00Z"), TAIPEI),
                    "家庭");
            return null;
        });

        CalendarQueryPage first = inContext(ownerContext, () ->
                queries.query(CalendarQueryFilter.search("電影", "娛樂", 1, 0)));
        CalendarQueryPage second = inContext(ownerContext, () ->
                queries.query(CalendarQueryFilter.search(
                        "電影", "娛樂", 1, first.nextOffset())));
        CalendarQueryPage peerResult = inContext(context(peer, workspace), () ->
                queries.query(CalendarQueryFilter.search("電影", null, 20, 0)));

        assertThat(first.items())
                .extracting(CalendarQueryItem::title)
                .containsExactly("電影聚餐");
        assertThat(first.nextOffset()).isEqualTo(1);
        assertThat(second.items())
                .extracting(CalendarQueryItem::title)
                .containsExactly("台北電影");
        assertThat(second.nextOffset()).isNull();
        assertThat(peerResult.items()).isEmpty();
    }

    @Test
    void wholePlanViewerCanSearchSharedNormalizedCategoryButUnsharedPeerCannot() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "共享分類 owner");
        seedUser(recipient, "共享分類 recipient");
        seedUser(peer, "未授權 peer");
        addMember(workspace, recipient, owner);
        addMember(workspace, peer, owner);
        WorkspaceContext ownerContext = context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> {
            var created = calendar.createPlanWithIdentity(
                    new CreateCalendarPlanCommand(
                            "shared-category-plan",
                            "港口接駁",
                            CalendarPlacement.point(
                                    Instant.parse("2027-09-01T01:00:00Z"),
                                    TAIPEI),
                            "交通 接駁",
                            null,
                            null,
                            List.of(),
                            List.of()));
            shares.createViewerShare(
                    "share-category-viewer", created.planId(), recipient, 1);
            return created.planId();
        });

        CalendarQueryPage ownerResult = inContext(
                ownerContext,
                () -> queries.query(CalendarQueryFilter.search(
                        "接駁", "交通 接駁", 20, 0)));
        CalendarQueryPage recipientResult = inContext(
                context(recipient, workspace),
                () -> queries.query(CalendarQueryFilter.search(
                        "接駁", "交通 接駁", 20, 0)));
        CalendarQueryPage peerResult = inContext(
                context(peer, workspace),
                () -> queries.query(CalendarQueryFilter.search(
                        "接駁", "交通 接駁", 20, 0)));

        assertThat(planId).isNotNull();
        assertThat(ownerResult.items())
                .extracting(CalendarQueryItem::category)
                .containsExactly("交通 接駁");
        assertThat(recipientResult.items())
                .extracting(CalendarQueryItem::title, CalendarQueryItem::category)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "港口接駁", "交通 接駁"));
        assertThat(peerResult.items()).isEmpty();
    }

    @Test
    void boundedQueryPathKeepsWarmP95BelowInteractiveBudget() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "效能查詢者");
        WorkspaceContext context = context(actor, workspace);
        inContext(context, () -> {
            for (int index = 0; index < 12; index++) {
                create(
                        "latency-" + index,
                        "效能電影 " + index,
                        CalendarPlacement.point(
                                Instant.parse("2026-07-25T01:00:00Z")
                                        .plus(Duration.ofHours(index)),
                                TAIPEI),
                        index % 2 == 0 ? "娛樂" : "家庭");
            }
            queries.query(CalendarQueryFilter.search("效能電影", null, 20, 0));
            return null;
        });

        List<Long> elapsedMillis = new ArrayList<>();
        for (int sample = 0; sample < 20; sample++) {
            long started = System.nanoTime();
            CalendarQueryPage page = inContext(context, () ->
                    queries.query(CalendarQueryFilter.search("效能電影", null, 20, 0)));
            elapsedMillis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
            assertThat(page.items()).hasSize(12);
        }
        Collections.sort(elapsedMillis);
        long p95 = elapsedMillis.get(18);

        assertThat(p95).isLessThanOrEqualTo(1_500L);
    }

    private void create(
            String requestKey, String title, CalendarPlacement placement, String category) {
        calendar.createPlan(new CreateCalendarPlanCommand(
                requestKey, title, placement, category, null, null, List.of(), List.of()));
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
    }

    private void addMember(UUID workspaceId, UUID actorId, UUID ownerId) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }
}
