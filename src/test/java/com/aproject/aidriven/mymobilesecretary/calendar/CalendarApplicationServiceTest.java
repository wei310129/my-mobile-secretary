package com.aproject.aidriven.mymobilesecretary.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarActivityDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarApplicationServiceTest extends IntegrationTestBase {

    private static final Instant START = Instant.parse("2026-07-25T03:00:00Z");

    @Autowired private CalendarApplicationService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsOneAtomicGraphAndReplaysTheSameRequestExactlyOnce() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "owner");
        WorkspaceContext context = context(actor, workspace);
        CreateCalendarPlanCommand command = command(
                "calendar-create-1",
                "裕隆城",
                "  親子   活動 ",
                "HTTPS://Meet.Google.com/abc",
                List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                        "departure", "離開", START.plus(Duration.ofHours(5))))));

        var created = inContext(context, () -> service.createPlan(command));
        var replayed = inContext(context, () -> service.createPlan(command));

        assertThat(replayed).isEqualTo(created);
        assertThat(created.title()).isEqualTo("裕隆城");
        assertThat(created.category()).isEqualTo("親子 活動");
        assertThat(created.onlineLinkHost()).contains("meet.google.com");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                        Long.class,
                        workspace))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_activity WHERE workspace_id = ?",
                        Long.class,
                        workspace))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_time_node WHERE workspace_id = ?",
                        Long.class,
                        workspace))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_online_access_link WHERE workspace_id = ?",
                        Long.class,
                        workspace))
                .isEqualTo(1L);
    }

    @Test
    void concurrentSameRequestReturnsOneCompleteGraphToBothCallers() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "concurrent calendar owner");
        WorkspaceContext context = context(actor, workspace);
        CreateCalendarPlanCommand command = command(
                "calendar-concurrent-1",
                "同一份行程圖",
                "親子",
                "https://meet.example.com/room",
                List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                        "departure", "離開", START.plus(Duration.ofHours(5))))));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first =
                    pool.submit(() -> concurrentCreate(context, command, ready, start));
            Future<?> second =
                    pool.submit(() -> concurrentCreate(context, command, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_plan WHERE workspace_id = ?",
                            Long.class,
                            workspace))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_activity WHERE workspace_id = ?",
                            Long.class,
                            workspace))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_time_node WHERE workspace_id = ?",
                            Long.class,
                            workspace))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_online_access_link WHERE workspace_id = ?",
                            Long.class,
                            workspace))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void conflictingReplayAndInvalidNestedNodeRollbackWithoutPartialGraph() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "rollback owner");
        WorkspaceContext context = context(actor, workspace);
        CreateCalendarPlanCommand original =
                command("calendar-create-2", "原始", null, null, List.of());
        inContext(context, () -> service.createPlan(original));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> service.createPlan(
                                command("calendar-create-2", "不同內容", null, null, List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different");

        CreateCalendarPlanCommand invalid = command(
                "calendar-create-3",
                "應回滾",
                null,
                null,
                List.of(CalendarNodeDraft.of(CalendarTimeNode.relativeToNode(
                        "bad",
                        "缺 base",
                        "missing",
                        Duration.ZERO,
                        Criticality.NORMAL,
                        Adjustability.FLEXIBLE))));
        assertThatThrownBy(() -> inContext(context, () -> service.createPlan(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_plan WHERE title = '應回滾'", Long.class))
                .isZero();

        CalendarActivityDraft duplicateChild = new CalendarActivityDraft(
                "會失敗的 child",
                CalendarPlacement.point(START.plusSeconds(1800), ZoneId.of("Asia/Taipei")),
                null,
                List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                        "duplicate", "activity duplicate", START.plusSeconds(1800)))));
        CreateCalendarPlanCommand databaseFailure = new CreateCalendarPlanCommand(
                "calendar-create-3b",
                "DB child 失敗也回滾",
                CalendarPlacement.interval(
                        START,
                        START.plus(Duration.ofHours(2)),
                        ZoneId.of("Asia/Taipei")),
                null,
                null,
                null,
                List.of(duplicateChild),
                List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                        "duplicate", "plan duplicate", START))));
        assertThatThrownBy(() -> inContext(
                        context, () -> service.createPlan(databaseFailure)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_plan WHERE title = 'DB child 失敗也回滾'",
                        Long.class))
                .isZero();
    }

    @Test
    void lockedNodeRequiresExplicitRevisionAndHttpsLinkIsFailClosed() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "revision owner");
        WorkspaceContext context = context(actor, workspace);
        inContext(context, () -> service.createPlan(command(
                "calendar-create-4",
                "電影",
                "娛樂",
                null,
                List.of(CalendarNodeDraft.of(CalendarTimeNode.absolute(
                        "showtime", "開演", START.plusSeconds(1800)))))));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> service.moveNodeOrdinarily(
                                "calendar-create-4",
                                "showtime",
                                START.plusSeconds(2400),
                                1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked");
        var revised = inContext(
                context,
                () -> service.reviseLockedNode(
                        "calendar-create-4", "showtime", START.plusSeconds(2400), 1));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.effectiveTime()).isEqualTo(START.plusSeconds(2400));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_personal_projection_signal
                        WHERE mutation_mode = 'GENERAL_REVIEW'
                          AND mutation_kind = 'NODE_TIME'
                          AND source_origin = 'OWNER'
                        """,
                        Long.class))
                .isEqualTo(1L);

        var canceled = inContext(
                context,
                () -> service.cancelNodeOrdinarily(
                        "calendar-create-4", "showtime", 2));
        assertThat(canceled.revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_personal_projection_signal
                        WHERE mutation_mode = 'GENERAL_REVIEW'
                          AND mutation_kind = 'NODE_CANCELLATION'
                          AND source_origin = 'OWNER'
                        """,
                        Long.class))
                .isEqualTo(1L);

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> service.setPlanOnlineLink(
                                "calendar-create-4", "http://internal.example/secret", "會議")))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_online_access_link", Long.class))
                .isZero();

        inContext(context, () -> service.setPlanOnlineLink(
                "calendar-create-4", "https://Example.com/meeting", "  主會議 "));
        assertThatThrownBy(() -> inContext(
                        context,
                        () -> service.setPlanOnlineLink(
                                "calendar-create-4", "https://example.com/other", "另一個")))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_online_access_link", Long.class))
                .isEqualTo(1L);

        var changed = inContext(
                context,
                () -> service.changePlanCategory("calendar-create-4", "  親子   電影 ", 1));
        assertThat(changed.category()).isEqualTo("親子 電影");
        assertThat(changed.revision()).isEqualTo(2);
    }

    private static CreateCalendarPlanCommand command(
            String requestKey,
            String title,
            String category,
            String onlineLink,
            List<CalendarNodeDraft> planNodes) {
        CalendarActivityDraft activity = new CalendarActivityDraft(
                "電影",
                CalendarPlacement.point(
                        START.plusSeconds(1800), ZoneId.of("Asia/Taipei")),
                "娛樂",
                List.of(CalendarNodeDraft.of(CalendarTimeNode.relativeToOwner(
                        "queue",
                        "排隊",
                        CalendarTimeNode.OwnerBoundary.START,
                        Duration.ofMinutes(-10),
                        Criticality.CRITICAL,
                        Adjustability.FLEXIBLE))));
        return new CreateCalendarPlanCommand(
                requestKey,
                title,
                CalendarPlacement.interval(
                        START, START.plus(Duration.ofHours(5)), ZoneId.of("Asia/Taipei")),
                category,
                onlineLink,
                "Google Meet",
                List.of(activity),
                planNodes);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
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

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private void inContext(WorkspaceContext context, Runnable work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            work.run();
        }
    }

    private Object concurrentCreate(
            WorkspaceContext context,
            CreateCalendarPlanCommand command,
            CountDownLatch ready,
            CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return inContext(context, () -> service.createPlan(command));
    }
}
