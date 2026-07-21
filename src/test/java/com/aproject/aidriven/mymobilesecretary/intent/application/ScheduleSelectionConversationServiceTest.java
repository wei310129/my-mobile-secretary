package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleSelectionConversationServiceTest {

    private ScheduleService schedules;
    private ConversationContextService context;
    private ScheduleSelectionConversationService service;
    private ScheduleItem first;
    private ScheduleItem second;

    @BeforeEach
    void setUp() {
        schedules = mock(ScheduleService.class);
        context = mock(ConversationContextService.class);
        service = new ScheduleSelectionConversationService(schedules, context);
        first = item(14L, "送女兒到夏恩英語上課", ScheduleStatus.CONFIRMED,
                "2026-07-25T02:00:00Z", "2026-07-25T04:00:00Z");
        second = item(16L, "女兒上夏恩英語", ScheduleStatus.PROPOSED,
                "2026-07-25T02:00:00Z", "2026-07-25T04:00:00Z");
        when(schedules.getSchedule(14L)).thenReturn(first);
        when(schedules.getSchedule(16L)).thenReturn(second);
    }

    @Test
    void realMergeConversationComposesSelectKeepAndDiscardWithoutAi() {
        when(context.snapshot()).thenReturn(snapshot(
                "可以。請回覆「保留第一個」或「保留第二個」。"));
        AtomicInteger mutations = new AtomicInteger();

        IntentResult question = service.answer(
                "前兩個行程合併成一個", "前兩個行程合併成一個",
                mutations::incrementAndGet).orElseThrow();

        assertThat(question.message())
                .contains("1.", "送女兒到夏恩英語上課", "2.", "女兒上夏恩英語",
                        "保留第一個", "保留第二個")
                .doesNotContain("系統沒有", "接的人不一定是你");
        assertThat(mutations).hasValue(0);

        IntentResult completed = service.answer(
                "1.對，保留第一個", "1.對，保留第一個",
                mutations::incrementAndGet).orElseThrow();

        assertThat(completed.message())
                .contains("已合併為一筆", "保留", "送女兒到夏恩英語上課",
                        "已放棄待確認行程", "女兒上夏恩英語");
        assertThat(mutations).hasValue(1);
        verify(schedules).discardSchedule(16L);
        verify(context).rememberSchedule(first);
    }

    @Test
    void scheduleOrdinalDeletionWinsOverKnowledgeOrdinalDeletion() {
        when(context.snapshot()).thenReturn(snapshot(null));
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service.answer(
                "刪除2.所指的行程", "刪除2.所指的行程",
                mutations::incrementAndGet).orElseThrow();

        assertThat(result.message()).contains("已放棄待確認行程", "女兒上夏恩英語")
                .doesNotContain("知識清單");
        assertThat(mutations).hasValue(1);
        verify(schedules).discardSchedule(16L);
        verify(schedules, never()).discardSchedule(14L);
    }

    @Test
    void typedLineQuoteResolvesExactScheduleEvenWhenMutableContextPointsElsewhere() {
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                null, 14L, null, List.of(), List.of(14L), null, null, null));
        when(schedules.listSchedules(null)).thenReturn(List.of(first, second));
        AtomicInteger mutations = new AtomicInteger();
        String interpretation = "【LINE 明確引用】行程（待確認）「女兒上夏恩英語」｜07/25 10:00–12:00\n"
                + "【LINE 引用參考】SCHEDULE:16:1\n【使用者目前訊息】刪除這個行程";

        IntentResult result = service.answer(
                "刪除這個行程", interpretation, mutations::incrementAndGet).orElseThrow();

        assertThat(result.message()).contains("女兒上夏恩英語");
        verify(schedules).discardSchedule(16L);
        verify(schedules, never()).discardSchedule(14L);
    }

    private ConversationSnapshot snapshot(String lastAssistant) {
        return new ConversationSnapshot(null, 16L, null, List.of(),
                List.of(14L, 16L), null, null, lastAssistant);
    }

    private static ScheduleItem item(
            long id, String title, ScheduleStatus status, String start, String end) {
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        when(item.getStatus()).thenReturn(status);
        when(item.getStartAt()).thenReturn(Instant.parse(start));
        when(item.getEndAt()).thenReturn(Instant.parse(end));
        return item;
    }
}
