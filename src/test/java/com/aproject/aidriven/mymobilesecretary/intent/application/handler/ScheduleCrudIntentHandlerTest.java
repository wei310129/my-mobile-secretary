package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2IntentService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarV2RoutingService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleCrudIntentHandlerTest {

    private ScheduleService schedules;
    private ConversationContextService context;
    private ScheduleMutationIntentHandler handler;

    @BeforeEach
    void setUp() {
        schedules = mock(ScheduleService.class);
        context = mock(ConversationContextService.class);
        handler = new ScheduleMutationIntentHandler(
                schedules, mock(PlaceAliasService.class), context,
                mock(BulkScheduleCancellationService.class),
                mock(CalendarV2RoutingService.class),
                mock(CalendarV2IntentService.class));
    }

    @Test
    void updatePatchesOnlyExplicitScheduleFields() {
        ScheduleItem source = item(10L, "看牙醫");
        ScheduleItem updated = item(10L, "定期洗牙");
        when(schedules.findReschedulableSchedulesMatching("看牙醫")).thenReturn(List.of(source));
        when(schedules.updateDetails(10L, "定期洗牙", null)).thenReturn(updated);
        IntentCommand command = command(IntentCommand.Type.UPDATE_SCHEDULE, "看牙醫", null, null,
                IntentOptions.empty().withNewTitle("定期洗牙"));

        IntentResult result = handler.handle("把看牙醫改名成定期洗牙", command);

        assertThat(result.message()).contains("已更新行程", "定期洗牙");
        verify(schedules).updateDetails(10L, "定期洗牙", null);
        verify(context).rememberSchedule(updated);
    }

    @Test
    void copyReusesTitlePlaceAndDurationAtTheNewTime() {
        ScheduleItem source = item(10L, "看牙醫");
        when(source.getStartAt()).thenReturn(Instant.parse("2026-07-10T02:00:00Z"));
        when(source.getEndAt()).thenReturn(Instant.parse("2026-07-10T03:00:00Z"));
        when(source.getPlaceId()).thenReturn(8L);
        when(source.getCategory()).thenReturn(ScheduleItem.Category.PERSONAL);
        ScheduleItem copied = item(11L, "看牙醫");
        var decision = new ScheduleService.ScheduleDecision(copied, null);
        when(schedules.findReschedulableSchedulesMatching("看牙醫")).thenReturn(List.of(source));
        when(schedules.createSchedule(
                eq("看牙醫"), eq(Instant.parse("2026-08-15T07:00:00Z")),
                eq(Instant.parse("2026-08-15T08:00:00Z")), eq(8L),
                eq(ScheduleItem.Recurrence.NONE), eq(null), eq(ScheduleItem.Category.PERSONAL)))
                .thenReturn(decision);
        IntentCommand command = command(IntentCommand.Type.COPY_SCHEDULE, null,
                "2026-08-15T15:00:00+08:00", null,
                IntentOptions.empty().withReferenceTitle("看牙醫"));

        IntentResult result = handler.handle("照上次看牙醫建立新行程", command);

        assertThat(result.message()).contains("沿用", "看牙醫");
        verify(context).rememberSchedule(copied);
    }

    @Test
    void mergeUsesExplicitPrimaryAndDuplicateReferences() {
        ScheduleItem kept = item(14L, "送女兒到夏恩英語上課");
        ScheduleItem duplicate = item(16L, "女兒上夏恩英語");
        when(schedules.findReschedulableSchedulesMatching(kept.getTitle()))
                .thenReturn(List.of(kept));
        when(schedules.findReschedulableSchedulesMatching(duplicate.getTitle()))
                .thenReturn(List.of(duplicate));
        when(schedules.mergeSchedules(14L, 16L))
                .thenReturn(new ScheduleService.ScheduleMerge(kept, duplicate, null));
        IntentCommand command = command(IntentCommand.Type.MERGE_SCHEDULES, kept.getTitle(),
                null, null, IntentOptions.empty().withReferenceTitle(duplicate.getTitle()));

        IntentResult result = handler.handle("合併兩筆並保留送女兒", command);

        assertThat(result.message()).contains("已合併為一筆", kept.getTitle(), duplicate.getTitle());
        verify(schedules).mergeSchedules(14L, 16L);
        verify(context).rememberSchedule(kept);
    }

    private static IntentCommand command(
            IntentCommand.Type type, String title, String startAt, String endAt,
            IntentOptions options) {
        return new IntentCommand(type, title, null, startAt, endAt, null, null, null,
                null, null, null, null, null, options);
    }

    private static ScheduleItem item(long id, String title) {
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        return item;
    }
}
