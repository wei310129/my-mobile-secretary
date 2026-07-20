package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskInsightService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskQueryIntentHandlerTest {

    private TaskQueryIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TaskQueryIntentHandler(
                mock(TaskService.class),
                mock(TaskInsightService.class),
                mock(ScheduleService.class),
                mock(GeofenceRuleService.class),
                mock(ConversationContextService.class),
                Clock.systemUTC());
    }

    @Test
    void registersEveryTaskQueryType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.LIST_TASKS,
                IntentCommand.Type.ASK_TASK_INFO,
                IntentCommand.Type.LIST_COMPLETED_TASKS,
                IntentCommand.Type.SUGGEST_NEXT_TASK,
                IntentCommand.Type.GROUP_TASKS_BY_CATEGORY,
                IntentCommand.Type.ASK_TASK_PROGRESS,
                IntentCommand.Type.GROUP_TASKS_BY_DUE,
                IntentCommand.Type.ASK_TASK_LOAD,
                IntentCommand.Type.ASK_BUSY_TASK_DAY));
    }

    @Test
    void emptyTaskListKeepsExistingReply() {
        IntentResult result = handler.handle("還有什麼", command(IntentCommand.Type.LIST_TASKS));

        assertThat(result.action()).isEqualTo(IntentResult.Action.TASKS_LISTED);
        assertThat(result.message()).isEqualTo(IntentResult.tasksListed(java.util.List.of(), "").message());
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
