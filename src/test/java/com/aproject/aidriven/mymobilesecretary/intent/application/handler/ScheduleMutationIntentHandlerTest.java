package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
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
    private CalendarIntentDraftConversationService calendarDrafts;

    @BeforeEach
    void setUp() {
        legacySchedules = mock(ScheduleService.class);
        routing = mock(CalendarV2RoutingService.class);
        calendarV2 = mock(CalendarV2IntentService.class);
        calendarDrafts = mock(CalendarIntentDraftConversationService.class);
        handler = new ScheduleMutationIntentHandler(
                legacySchedules,
                mock(PlaceAliasService.class),
                mock(ConversationContextService.class),
                mock(BulkScheduleCancellationService.class),
                routing,
                calendarV2,
                calendarDrafts);
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
    void cutoverCreateUsesDraftPreflightLane() {
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
        var expected = com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult
                .suggestionMade("先確認路線風險");
        when(routing.useCalendarV2()).thenReturn(true);
        when(calendarDrafts.createWithPreflight(command)).thenReturn(expected);

        var result = handler.handle("明天九點建立客戶會議", command);

        assertThat(result).isSameAs(expected);
        verify(calendarDrafts).createWithPreflight(command);
        verifyNoInteractions(legacySchedules, calendarV2);
    }

    @Test
    void recurringCutoverCreateUsesTheSameDraftPreflightLane() {
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "每週例會",
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
                true,
                IntentOptions.empty());
        var expected = com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult
                .suggestionMade("固定行程已通過草稿預檢");
        when(routing.useCalendarV2()).thenReturn(true);
        when(calendarDrafts.createWithPreflight(command)).thenReturn(expected);

        var result = handler.handle("每週五九點建立例會", command);

        assertThat(result).isSameAs(expected);
        verify(calendarDrafts).createWithPreflight(command);
        verifyNoInteractions(legacySchedules, calendarV2);
    }
}
