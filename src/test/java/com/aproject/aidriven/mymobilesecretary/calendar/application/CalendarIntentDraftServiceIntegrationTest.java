package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarIntentDraftServiceIntegrationTest extends IntegrationTestBase {

    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void proposalPersistsTypedPendingStateWithoutCreatingCalendarGraph() {
        Fixture fixture = fixture();

        var proposed = run(fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));

        assertThat(proposed.status()).isEqualTo(Status.PENDING);
        assertThat(proposed.revision()).isEqualTo(1L);
        assertThat(proposed.placement()).isInstanceOf(CalendarPlacement.TimedInterval.class);
        assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
        assertThat(count("calendar_plan", fixture.workspace())).isZero();
        assertThat(count("calendar_time_node", fixture.workspace())).isZero();
    }

    @Test
    void sameInboundRequestReplaysOneDraftExactly() {
        Fixture fixture = fixture();
        UUID request = UUID.randomUUID();

        var first = run(fixture.owner(), request, () -> drafts.propose(command()));
        var replay = run(fixture.owner(), request, () -> drafts.propose(command()));

        assertThat(replay).isEqualTo(first);
        assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
    }

    @Test
    void separateInboundRequestsCanKeepParallelDrafts() {
        Fixture fixture = fixture();

        var first = run(fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));
        var second = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command("晚餐")));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(2L);
        assertThat(count("calendar_plan", fixture.workspace())).isZero();
    }

    @Test
    void correctionUsesExpectedRevisionAndPreservesIntervalDuration() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));
        IntentCommand correction = correction(
                "改名後的會議", "2026-09-10T16:00:00+08:00", null);

        var revised = in(fixture.owner(), () -> drafts.revise(
                proposed.id(), proposed.revision(), correction));

        var placement = (CalendarPlacement.TimedInterval) revised.placement();
        assertThat(revised.title()).isEqualTo("改名後的會議");
        assertThat(revised.revision()).isEqualTo(2L);
        assertThat(Duration.between(placement.start(), placement.end()))
                .isEqualTo(Duration.ofHours(1));
        assertThat(placement.start()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
        assertThat(count("calendar_plan", fixture.workspace())).isZero();
    }

    @Test
    void endOnlyCorrectionKeepsStartAndChangesEnd() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));

        var revised = in(fixture.owner(), () -> drafts.revise(
                proposed.id(), 1L, correction(null, null, "2026-09-10T16:30:00+08:00")));

        var placement = (CalendarPlacement.TimedInterval) revised.placement();
        assertThat(placement.start()).isEqualTo(Instant.parse("2026-09-10T07:00:00Z"));
        assertThat(placement.end()).isEqualTo(Instant.parse("2026-09-10T08:30:00Z"));
    }

    @Test
    void staleCorrectionFailsWithoutMutation() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));
        in(fixture.owner(), () -> drafts.revise(
                proposed.id(), 1L, correction("新版", null, null)));

        assertThatThrownBy(() -> in(fixture.owner(), () -> drafts.revise(
                        proposed.id(), 1L, correction("過期修改", null, null))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("STALE_CALENDAR_DRAFT");
        assertThat(in(fixture.owner(), () -> drafts.get(proposed.id())).title())
                .isEqualTo("新版");
    }

    @Test
    void confirmationMaterializesOneCalendarGraphAndReplaysExactly() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));

        var saved = in(fixture.owner(), () -> drafts.materialize(proposed.id(), 1L));
        var replay = in(fixture.owner(), () -> drafts.materialize(proposed.id(), 1L));

        assertThat(replay).isEqualTo(saved);
        assertThat(saved.status()).isEqualTo(Status.MATERIALIZED);
        assertThat(saved.revision()).isEqualTo(2L);
        assertThat(saved.materializedPlanId()).isNotNull();
        assertThat(in(fixture.owner(), () -> drafts.isAvailableForFocus(saved.id()))).isTrue();
        assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        assertThat(count("calendar_time_node", fixture.workspace())).isEqualTo(2L);
    }

    @Test
    void recurringProposalRegistersExactlyOneTypedSeriesAtMaterialization() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(recurringCommand()));

        var saved = in(fixture.owner(), () -> drafts.materialize(proposed.id(), 1L));
        var replay = in(fixture.owner(), () -> drafts.materialize(proposed.id(), 1L));

        assertThat(proposed.recurrenceRule()).isEqualTo("WEEKLY");
        assertThat(replay).isEqualTo(saved);
        assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        assertThat(count("calendar_recurrence_series", fixture.workspace())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT rule.frequency
                        FROM calendar_recurrence_rule_revision rule
                        JOIN calendar_recurrence_series series ON series.id = rule.series_id
                        WHERE series.plan_id = ? AND rule.state = 'ACTIVE'
                        """,
                        String.class,
                        saved.materializedPlanId()))
                .isEqualTo("WEEKLY");
    }

    @Test
    void discardReplaysAndNeverCreatesCalendarGraph() {
        Fixture fixture = fixture();
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));

        var discarded = in(fixture.owner(), () -> drafts.discard(proposed.id(), 1L));
        var replay = in(fixture.owner(), () -> drafts.discard(proposed.id(), 1L));

        assertThat(replay).isEqualTo(discarded);
        assertThat(discarded.status()).isEqualTo(Status.DISCARDED);
        assertThat(in(fixture.owner(), () -> drafts.isAvailableForFocus(discarded.id()))).isFalse();
        assertThat(count("calendar_plan", fixture.workspace())).isZero();
    }

    @Test
    void actorAndConversationScopeCannotReadAnotherDraft() {
        Fixture fixture = fixture();
        UUID peer = UUID.randomUUID();
        seedUser(peer, "peer");
        var proposed = run(
                fixture.owner(), UUID.randomUUID(), () -> drafts.propose(command()));
        WorkspaceContext peerContext = context(peer, fixture.workspace(), "scope-a");
        WorkspaceContext otherScope = context(
                fixture.actor(), fixture.workspace(), "scope-b");

        assertThatThrownBy(() -> in(peerContext, () -> drafts.get(proposed.id())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> in(otherScope, () -> drafts.get(proposed.id())))
                .isInstanceOf(NotFoundException.class);
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(actor, "owner");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'draft workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
        return new Fixture(actor, workspace, context(actor, workspace, "scope-a"));
    }

    private void seedUser(UUID actor, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor,
                label + actor.toString().substring(0, 6));
    }

    private long count(String table, UUID workspace) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspace);
    }

    private <T> T run(WorkspaceContext context, UUID request, Supplier<T> work) {
        try (RequestCorrelationContext.Scope ignoredRequest =
                        RequestCorrelationContext.open(request);
                WorkspaceContextHolder.Scope ignoredContext =
                        WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T in(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private static WorkspaceContext context(UUID actor, UUID workspace, String token) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST, "h4-test", token);
    }

    private static IntentCommand command() {
        return command("客戶會議");
    }

    private static IntentCommand command(String title) {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                title,
                null,
                "2026-09-10T15:00:00+08:00",
                "2026-09-10T16:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty().withCategory("工作"),
                "9月10日下午三點到四點" + title);
    }

    private static IntentCommand correction(String title, String start, String end) {
        return new IntentCommand(
                IntentCommand.Type.UPDATE_SCHEDULE,
                title,
                null,
                start,
                end,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty(),
                "correction");
    }

    private static IntentCommand recurringCommand() {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "每週例會",
                null,
                "2026-09-11T15:00:00+08:00",
                "2026-09-11T16:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                IntentOptions.empty(),
                "每週五下午三點到四點開例會");
    }

    private record Fixture(UUID actor, UUID workspace, WorkspaceContext owner) {}
}
