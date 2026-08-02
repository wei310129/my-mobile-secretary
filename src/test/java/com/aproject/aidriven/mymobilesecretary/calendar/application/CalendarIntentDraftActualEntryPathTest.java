package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class CalendarIntentDraftActualEntryPathTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intents;
    @Autowired private CalendarIntentDraftService drafts;
    @Autowired private ConversationFocusService focuses;
    @Autowired private ConversationFocusQuoteResolver quotes;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void actualEntryReplaysProposalAndMaterializesOnlyAfterConfirmation() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            String text = "9月10日下午三點到四點客戶會議，只留草稿，不要直接建立";
            IntentCommand proposal = create("客戶會議", text);
            UUID request = UUID.randomUUID();
            interpreter.nextCommand(proposal);

            IntentResult first = RequestCorrelationContext.run(
                    request, () -> intents.handle(text, "TEST"));
            IntentResult replay = RequestCorrelationContext.run(
                    request, () -> intents.handle(text, "TEST"));

            assertThat(replay.message()).isEqualTo(first.message());
            assertThat(first.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            assertThat(first.responseEnvelope().message())
                    .contains("尚未放進行事曆", "要照這個版本建立嗎")
                    .doesNotContain("Intent", "reason=", "revision:", "nodeId");
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();
            assertThat(focuses.activeFocus()).hasValueSatisfying(focus -> {
                assertThat(focus.getRootDomain())
                        .isEqualTo(CalendarIntentDraftConversationService.FOCUS_DOMAIN);
                assertThat(focus.getActivityCode()).isEqualTo("revision:1");
            });

            String confirmation = "好，就照這個版本建立";
            interpreter.nextCommand(context(IntentCommand.Type.ACCEPT_CONTEXT, confirmation));
            IntentResult confirmed = RequestCorrelationContext.run(
                    UUID.randomUUID(), () -> intents.handle(confirmation, "TEST"));

            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(confirmed.message()).contains("已把", "放進行事曆");
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
            assertThat(count("calendar_intent_draft", fixture.workspace())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status FROM calendar_intent_draft
                            WHERE workspace_id = ?
                            """,
                            String.class,
                            fixture.workspace()))
                    .isEqualTo("MATERIALIZED");
            assertThat(focuses.activeFocus()).isEmpty();
        }
    }

    @Test
    void quotedParallelDraftWinsCorrectionAndCanConfirmAfterRename() {
        Fixture fixture = fixture();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(fixture.context())) {
            IntentResult firstResult = handle(
                    "9月10日下午三點到四點會議，先留草稿",
                    create("會議", "9月10日下午三點到四點會議，先留草稿"));
            assertThat(firstResult.action()).isEqualTo(IntentResult.Action.SUGGESTION_MADE);
            UUID firstDraft = focuses.activeFocus().orElseThrow().getWorkflowId();
            UUID firstFocus = focuses.activeFocus().orElseThrow().getId();

            handle(
                    "9月11日晚上六點到七點聚餐，只留草稿",
                    new IntentCommand(
                            IntentCommand.Type.CREATE_SCHEDULE,
                            "聚餐",
                            null,
                            "2026-09-11T18:00:00+08:00",
                            "2026-09-11T19:00:00+08:00",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            IntentOptions.empty(),
                            "9月11日晚上六點到七點聚餐，只留草稿"));
            UUID secondDraft = focuses.activeFocus().orElseThrow().getWorkflowId();
            assertThat(secondDraft).isNotEqualTo(firstDraft);
            assertThat(count("calendar_plan", fixture.workspace())).isZero();

            var resolution = quotes.resolveSuspendedFocus(firstFocus);
            assertThat(resolution.control().focusId()).isEqualTo(firstFocus);
            focuses.resume(resolution.control().focusId(), "d".repeat(64));

            String correctionText = "改叫季度會議，並改到下午四點";
            IntentCommand correction = new IntentCommand(
                    IntentCommand.Type.RESCHEDULE_SCHEDULE,
                    null,
                    null,
                    "2026-09-10T16:00:00+08:00",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    IntentOptions.empty().withNewTitle("季度會議"),
                    correctionText);
            IntentResult revised = handle(correctionText, correction);

            assertThat(revised.message()).contains("季度會議", "還沒有放進行事曆");
            var first = drafts.get(firstDraft);
            var second = drafts.get(secondDraft);
            assertThat(first.title()).isEqualTo("季度會議");
            assertThat(first.revision()).isEqualTo(2L);
            assertThat(((CalendarPlacement.TimedInterval) first.placement()).start())
                    .isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
            assertThat(second.title()).isEqualTo("聚餐");
            assertThat(second.revision()).isEqualTo(1L);

            IntentResult confirmed = handle(
                    "確認這個新版",
                    context(IntentCommand.Type.ACCEPT_CONTEXT, "確認這個新版"));
            assertThat(confirmed.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(drafts.get(firstDraft).status()).isEqualTo(Status.MATERIALIZED);
            assertThat(drafts.get(secondDraft).status()).isEqualTo(Status.PENDING);
            assertThat(count("calendar_plan", fixture.workspace())).isEqualTo(1L);
        }
    }

    private IntentResult handle(String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(text, "TEST"));
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'H4 actual entry', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'H4 actual entry', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                actor);
        return new Fixture(
                workspace,
                new WorkspaceContext(
                        actor, workspace, WorkspaceChannel.TEST, "h4-entry", "conversation"));
    }

    private long count(String table, UUID workspace) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class,
                workspace);
    }

    private static IntentCommand create(String title, String source) {
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
                false,
                IntentOptions.empty(),
                source);
    }

    private static IntentCommand context(IntentCommand.Type type, String source) {
        return new IntentCommand(
                type,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                source);
    }

    private record Fixture(UUID workspace, WorkspaceContext context) {}
}
