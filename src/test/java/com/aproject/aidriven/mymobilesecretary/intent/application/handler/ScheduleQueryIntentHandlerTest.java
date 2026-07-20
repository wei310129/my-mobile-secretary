package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.DailyScheduleOverviewService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleInsightService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduleQueryIntentHandlerTest {

    private ScheduleQueryIntentHandler handler;
    private ScheduleInsightService scheduleInsightService;

    @BeforeEach
    void setUp() {
        scheduleInsightService = mock(ScheduleInsightService.class);
        handler = new ScheduleQueryIntentHandler(
                mock(ScheduleService.class),
                mock(TaskService.class),
                mock(ConversationContextService.class),
                mock(FreeSlotService.class),
                scheduleInsightService,
                mock(PlaceService.class),
                mock(DailyScheduleOverviewService.class),
                Clock.systemUTC());
    }

    @Test
    void registersEveryScheduleQueryType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.LIST_SCHEDULES,
                IntentCommand.Type.ASK_SCHEDULE_INFO,
                IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
                IntentCommand.Type.LIST_AGENDA,
                IntentCommand.Type.ASK_AVAILABILITY,
                IntentCommand.Type.AGENDA_SUMMARY,
                IntentCommand.Type.ASK_NEXT_SCHEDULE,
                IntentCommand.Type.ASK_SCHEDULE_GAP,
                IntentCommand.Type.GROUP_SCHEDULES_BY_DAY,
                IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS,
                IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                IntentCommand.Type.GROUP_SCHEDULES_BY_PLACE));
    }

    @Test
    void emptyScheduleListKeepsExistingReply() {
        IntentResult result = handler.handle(
                "今天有什麼行程", command(IntentCommand.Type.LIST_SCHEDULES));

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULES_LISTED);
        assertThat(result.message()).isEqualTo(
                IntentResult.schedulesListed(java.util.List.of()).message());
    }

    @Test
    void scheduleLoadQueryExcludesItemsOutsideTheSuppliedRange() {
        ScheduleItem tomorrow = schedule("明天", "2026-07-19T02:00:00Z");
        ScheduleItem nextDay = schedule("後天", "2026-07-20T02:00:00Z");
        when(scheduleInsightService.upcoming()).thenReturn(List.of(tomorrow, nextDay));
        when(scheduleInsightService.busiestDay(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Optional.empty());

        handler.handle("明天行程排太滿嗎", new IntentCommand(
                IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                null, null, "2026-07-19T00:00:00+08:00",
                "2026-07-20T00:00:00+08:00", null, null, null,
                null, null, null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleItem>> candidates = ArgumentCaptor.forClass(List.class);
        verify(scheduleInsightService).busiestDay(candidates.capture());
        assertThat(candidates.getValue()).containsExactly(tomorrow);
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static ScheduleItem schedule(String title, String start) {
        Instant startAt = Instant.parse(start);
        return ScheduleItem.propose(
                title, startAt, startAt.plusSeconds(3600), null,
                Instant.parse("2026-07-18T00:00:00Z"));
    }
}
