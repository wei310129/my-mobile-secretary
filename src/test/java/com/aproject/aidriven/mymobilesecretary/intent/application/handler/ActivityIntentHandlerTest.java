package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityIntentHandlerTest {

    private ActivityIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ActivityIntentHandler(
                mock(TaskService.class),
                mock(ScheduleService.class),
                mock(ScheduleFollowUpService.class),
                mock(ConversationContextService.class));
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

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
