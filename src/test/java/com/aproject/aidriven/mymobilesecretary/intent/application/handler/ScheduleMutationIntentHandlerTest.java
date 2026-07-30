package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2IntentService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleMutationIntentHandlerTest {

    private ScheduleMutationIntentHandler handler;
    private ScheduleService legacySchedules;
    private CalendarV2RoutingService routing;
    private CalendarV2IntentService calendarV2;

    @BeforeEach
    void setUp() {
        legacySchedules = mock(ScheduleService.class);
        routing = mock(CalendarV2RoutingService.class);
        calendarV2 = mock(CalendarV2IntentService.class);
        handler = new ScheduleMutationIntentHandler(
                legacySchedules,
                mock(PlaceAliasService.class),
                mock(ConversationContextService.class),
                mock(BulkScheduleCancellationService.class),
                routing,
                calendarV2);
    }

    @Test
    void registersEveryScheduleMutationType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.CREATE_SCHEDULE,
                IntentCommand.Type.UPDATE_SCHEDULE,
                IntentCommand.Type.COPY_SCHEDULE,
                IntentCommand.Type.MERGE_SCHEDULES,
                IntentCommand.Type.CREATE_RELATIVE_SCHEDULE,
                IntentCommand.Type.CANCEL_SCHEDULE,
                IntentCommand.Type.RESCHEDULE_SCHEDULE,
                IntentCommand.Type.SET_SCHEDULE_RECURRING,
                IntentCommand.Type.RESIZE_SCHEDULE,
                IntentCommand.Type.BULK_CANCEL_SCHEDULES));
    }

    @Test
    void cutoverCreateDelegatesOnlyToCalendarV2() {
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "客戶會議",
                null,
                "2026-07-31T09:00:00+08:00",
                "2026-07-31T10:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty());
        when(routing.useCalendarV2()).thenReturn(true);

        handler.handle("明天九點建立客戶會議", command);

        verify(calendarV2).create(command);
        verifyNoInteractions(legacySchedules);
    }
}
