package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2IntentService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CalendarIntentDraftHandlerTest {

    @Test
    void explicitDraftRequestUsesOnlyTypedDraftLane() {
        ScheduleService legacy = mock(ScheduleService.class);
        CalendarV2IntentService calendarV2 = mock(CalendarV2IntentService.class);
        CalendarV2RoutingService routing = mock(CalendarV2RoutingService.class);
        CalendarIntentDraftConversationService drafts =
                mock(CalendarIntentDraftConversationService.class);
        ScheduleMutationIntentHandler handler = scheduleHandler(
                legacy, calendarV2, routing, drafts);
        IntentCommand command = createCommand();
        IntentResult expected = IntentResult.suggestionMade("先整理好了，要建立嗎？");
        when(routing.useCalendarV2()).thenReturn(true);
        when(drafts.propose(command)).thenReturn(expected);

        IntentResult result = handler.handle("只留草稿，不要直接建立", command);

        assertThat(result).isSameAs(expected);
        verify(drafts).propose(command);
        verifyNoInteractions(legacy, calendarV2);
    }

    @Test
    void disabledCalendarV2FailsClosedWithoutAnyMutation() {
        ScheduleService legacy = mock(ScheduleService.class);
        CalendarV2IntentService calendarV2 = mock(CalendarV2IntentService.class);
        CalendarV2RoutingService routing = mock(CalendarV2RoutingService.class);
        CalendarIntentDraftConversationService drafts =
                mock(CalendarIntentDraftConversationService.class);
        ScheduleMutationIntentHandler handler = scheduleHandler(
                legacy, calendarV2, routing, drafts);
        when(routing.useCalendarV2()).thenReturn(false);

        IntentResult result = handler.handle(
                "先不要建立正式，只留草稿", createCommand());

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("沒有新增資料");
        verifyNoInteractions(legacy, calendarV2, drafts);
    }

    @Test
    void correctionOfActiveDraftReturnsBeforeLegacyScheduleMutation() {
        ScheduleService legacy = mock(ScheduleService.class);
        CalendarV2IntentService calendarV2 = mock(CalendarV2IntentService.class);
        CalendarV2RoutingService routing = mock(CalendarV2RoutingService.class);
        CalendarIntentDraftConversationService drafts =
                mock(CalendarIntentDraftConversationService.class);
        ScheduleMutationIntentHandler handler = scheduleHandler(
                legacy, calendarV2, routing, drafts);
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
                IntentOptions.empty(),
                "改成下午四點");
        IntentResult expected = IntentResult.suggestionMade("已改好，要建立嗎？");
        when(drafts.reviseActive(correction)).thenReturn(Optional.of(expected));

        IntentResult result = handler.handle("改成下午四點", correction);

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(legacy, calendarV2, routing);
    }

    @Test
    void contextConfirmationAndDiscardPreferActiveCalendarDraft() {
        CalendarIntentDraftConversationService drafts =
                mock(CalendarIntentDraftConversationService.class);
        ConversationContextService legacyContext = mock(ConversationContextService.class);
        ContextIntentHandler handler = new ContextIntentHandler(
                mock(TaskService.class),
                mock(ScheduleService.class),
                legacyContext,
                mock(PlaceAliasService.class),
                mock(PlaceService.class),
                mock(GeofenceRuleService.class),
                mock(BulkScheduleCancellationService.class),
                drafts);
        IntentResult confirmed = IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED, "已放進行事曆");
        IntentResult discarded = IntentResult.message(
                IntentResult.Action.CONTEXT_UPDATED, "已放棄提案");
        when(drafts.confirmActive()).thenReturn(Optional.of(confirmed));
        when(drafts.discardActive()).thenReturn(Optional.of(discarded));

        assertThat(handler.handle("就照這個版本", context(IntentCommand.Type.ACCEPT_CONTEXT)))
                .isSameAs(confirmed);
        assertThat(handler.handle("這個不要了", context(IntentCommand.Type.CANCEL_CONTEXT)))
                .isSameAs(discarded);
        verifyNoInteractions(legacyContext);
    }

    private static ScheduleMutationIntentHandler scheduleHandler(
            ScheduleService legacy,
            CalendarV2IntentService calendarV2,
            CalendarV2RoutingService routing,
            CalendarIntentDraftConversationService drafts) {
        return new ScheduleMutationIntentHandler(
                legacy,
                mock(PlaceAliasService.class),
                mock(ConversationContextService.class),
                mock(BulkScheduleCancellationService.class),
                routing,
                calendarV2,
                drafts);
    }

    private static IntentCommand createCommand() {
        return new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "客戶會議",
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
                IntentOptions.empty(),
                "9月10日下午三點到四點客戶會議");
    }

    private static IntentCommand context(IntentCommand.Type type) {
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
                null,
                IntentOptions.empty(),
                null);
    }
}
