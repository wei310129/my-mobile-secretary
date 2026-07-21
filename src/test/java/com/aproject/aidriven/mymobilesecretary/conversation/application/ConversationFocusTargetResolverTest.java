package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import org.junit.jupiter.api.Test;

class ConversationFocusTargetResolverTest {

    private final ConversationFocusTargetResolver resolver = new ConversationFocusTargetResolver();

    @Test
    void usesPersistedTaskResultRatherThanFreeFormCommandText() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(42L);
        when(task.getTitle()).thenReturn("繳電費");
        IntentCommand command = command(IntentCommand.Type.CREATE_TASK, "任意文字不可當作 target");
        IntentResult result = new IntentResult(IntentResult.Action.TASK_CREATED, "已建立", task, null);

        assertThat(resolver.resolve(command, result))
                .contains(new ConversationFocusTargetResolver.ResourceTarget(
                        "TASK", "task:42", "繳電費"));
    }

    @Test
    void usesPersistedScheduleDecisionForCandidateReschedule() {
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(8L);
        when(item.getTitle()).thenReturn("醫院回診");
        ScheduleDecision decision = mock(ScheduleDecision.class);
        when(decision.item()).thenReturn(item);
        IntentResult result = new IntentResult(IntentResult.Action.SCHEDULE_RESCHEDULED,
                "已改期", null, decision);

        assertThat(resolver.resolve(command(IntentCommand.Type.RESCHEDULE_SCHEDULE, "無關文字"), result))
                .contains(new ConversationFocusTargetResolver.ResourceTarget(
                        "SCHEDULE", "schedule:8", "醫院回診"));
    }

    @Test
    void oneShotReplyWithoutAPersistedTargetCannotInventFocus() {
        IntentResult weather = IntentResult.message(IntentResult.Action.WEATHER_INFO, "晴天");

        assertThat(resolver.resolve(command(IntentCommand.Type.ASK_WEATHER, "天氣"), weather))
                .isEmpty();
    }

    private static IntentCommand command(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
