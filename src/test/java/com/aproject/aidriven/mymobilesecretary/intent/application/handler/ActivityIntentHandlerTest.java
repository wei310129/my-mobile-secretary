package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityIntentHandlerTest {

    private ActivityIntentHandler handler;
    private TaskService taskService;
    private ConversationContextService contextService;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        contextService = mock(ConversationContextService.class);
        handler = new ActivityIntentHandler(
                taskService,
                mock(ScheduleService.class),
                mock(ScheduleFollowUpService.class),
                contextService);
    }

    @Test
    void registersEveryActivityAndFeedbackType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.LIST_RECENT,
                IntentCommand.Type.RECORD_OUTCOME,
                IntentCommand.Type.FEEDBACK));
    }

    @Test
    void emptyRecentActivityKeepsExistingReply() {
        IntentResult result = handler.handle("最近新增什麼", command(IntentCommand.Type.LIST_RECENT));

        assertThat(result.action()).isEqualTo(IntentResult.Action.RECENT_ACTIVITY_LISTED);
        assertThat(result.message()).isEqualTo(IntentResult.message(
                IntentResult.Action.RECENT_ACTIVITY_LISTED, "最近沒有新增內容。").message());
    }

    @Test
    void modelDuplicateReasonCannotOverrideTheUsersExplicitNegation() {
        IntentResult result = handler.handle(
                "不是重複建立，而是不應該亂回答",
                feedback("DUPLICATE"));

        assertThat(result.message())
                .contains("理解錯了", "不會建立或修改")
                .doesNotContain("你提醒得對", "不應該重複建立");
    }

    @Test
    void duplicateComplaintWithoutDatabaseEvidenceReportsTheCheckTruthfully() {
        when(taskService.listOpenTasks()).thenReturn(List.of());

        IntentResult result = handler.handle(
                "你是不是又重複建立待辦了？",
                feedback("DUPLICATE"));

        assertThat(result.message())
                .contains("沒有完全同名的重複項目", "沒有刪除或修改")
                .doesNotContain("你提醒得對，不應該重複建立");
    }

    @Test
    void duplicateDiagnosisIsOnlyConfirmedWhenTheDatabaseHasEvidence() {
        Task first = Task.create("買牛奶", null, TaskPriority.NORMAL, null, Instant.EPOCH);
        Task second = Task.create("買牛奶", null, TaskPriority.NORMAL, null, Instant.EPOCH);
        when(taskService.listOpenTasks()).thenReturn(List.of(first, second));

        IntentResult result = handler.handle(
                "你剛剛重複建立買牛奶了",
                feedback("DUPLICATE"));

        assertThat(result.message()).contains("已確認", "買牛奶", "× 2");
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static IntentCommand feedback(String reason) {
        return new IntentCommand(IntentCommand.Type.FEEDBACK, null, null, null, null,
                null, null, reason, null, null, null, null, null);
    }
}
