package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RestaurantBookingDraftActualEntryTest extends IntegrationTestBase {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired private IntentService intentService;
    @Autowired private StubIntentInterpreter stub;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void multiTurnGuidancePersistsTypedAnswersAndNeverClaimsOrExecutesABooking() {
        WorkspaceContext context = new WorkspaceContext(
                ACTOR, WORKSPACE, WorkspaceChannel.TEST, "booking-guidance", "multi");
        long tasksBefore = count("task");
        long schedulesBefore = count("schedule_item");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            stub.nextCommand(booking(null, null, null));
            IntentResult first = handle("請幫我整理餐廳訂位資訊", 1);
            assertThat(first.nextQuestion().code()).isEqualTo("booking.restaurant");

            stub.nextCommand(booking("測試餐廳", null, null));
            IntentResult second = handle("測試餐廳", 2);
            assertThat(second.nextQuestion().code()).isEqualTo("booking.dining-at");

            stub.nextCommand(booking(null, "2099-08-04T19:00:00+08:00", null));
            IntentResult third = handle("2099年8月4日晚上七點", 3);
            assertThat(third.nextQuestion().code()).isEqualTo("booking.party-size");

            stub.nextCommand(booking(null, null, 4));
            IntentResult completed = handle("四位", 4);
            assertThat(completed.action()).isEqualTo(IntentResult.Action.RESTAURANT_BOOKING_INFO);
            assertThat(completed.nextQuestion()).isNull();
            assertThat(completed.message())
                    .contains("目前沒有向餐廳送出訂位或付款")
                    .doesNotContain("已訂位", "已付款", "完成後會通知");
            assertThat(jdbc.queryForMap("""
                    SELECT restaurant_name, party_size, status
                    FROM restaurant_booking_draft
                    WHERE workspace_id = ? AND created_by_user_id = ?
                    """, WORKSPACE, ACTOR))
                    .containsEntry("restaurant_name", "測試餐廳")
                    .containsEntry("party_size", 4)
                    .containsEntry("status", "COMPLETED");
            assertThat(answeredBookingQuestionCount()).isEqualTo(1);
        }

        assertThat(count("task")).isEqualTo(tasksBefore);
        assertThat(count("schedule_item")).isEqualTo(schedulesBefore);
        assertThat(forbiddenColumns()).isEmpty();
    }

    private IntentResult handle(String text, int sequence) {
        UUID requestId = UUID.fromString("23000000-0000-0000-0000-%012d".formatted(sequence));
        return RequestCorrelationContext.run(requestId,
                () -> intentService.handle(text, "TEST"));
    }

    private static IntentCommand booking(String restaurant, String startAt, Integer partySize) {
        IntentOptions options = new IntentOptions(null, null, null, null, null, null, null,
                null, null, partySize, null, null, null, null, null, null, null, null, null,
                null, null, null);
        return new IntentCommand(IntentCommand.Type.BOOK_RESTAURANT, null, null, startAt, null,
                restaurant, null, null, null, null, null, null, null, options);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long answeredBookingQuestionCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM conversation_pending_question
                WHERE question_code LIKE 'booking.%' AND status = 'ANSWERED'
                """, Long.class);
    }

    private java.util.List<String> forbiddenColumns() {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'restaurant_booking_draft'
                  AND (data_type IN ('json', 'jsonb') OR column_name IN (
                    'prompt', 'message', 'raw_text', 'payload', 'slots', 'user_text', 'reply'))
                """, String.class);
    }
}
