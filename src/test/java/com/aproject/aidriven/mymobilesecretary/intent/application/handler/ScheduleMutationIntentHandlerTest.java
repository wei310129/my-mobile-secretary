package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleMutationIntentHandlerTest {

    private ScheduleMutationIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ScheduleMutationIntentHandler(
                mock(ScheduleService.class),
                mock(PlaceAliasService.class),
                mock(ConversationContextService.class),
                mock(BulkScheduleCancellationService.class),
                mock(TaskMutationIntentHandler.class));
    }

    @Test
    void registersEveryScheduleMutationType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.CREATE_SCHEDULE,
                IntentCommand.Type.CREATE_RELATIVE_SCHEDULE,
                IntentCommand.Type.CANCEL_SCHEDULE,
                IntentCommand.Type.RESCHEDULE_SCHEDULE,
                IntentCommand.Type.SET_SCHEDULE_RECURRING,
                IntentCommand.Type.RESIZE_SCHEDULE,
                IntentCommand.Type.BULK_CANCEL_SCHEDULES));
    }
}
