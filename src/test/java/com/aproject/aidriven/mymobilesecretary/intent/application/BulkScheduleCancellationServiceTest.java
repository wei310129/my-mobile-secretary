package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 裁決 #49:批次刪除只能刪指定時間段內的非固定行程;固定行程要逐一指名。 */
@ExtendWith(MockitoExtension.class)
class BulkScheduleCancellationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final Instant FROM = Instant.parse("2026-07-20T00:00:00+08:00");
    private static final Instant TO = Instant.parse("2026-07-23T00:00:00+08:00");

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ConversationContextService contextService;

    private BulkScheduleCancellationService service;

    @BeforeEach
    void setUp() {
        service = new BulkScheduleCancellationService(scheduleService, contextService);
    }

    @Test
    void missingRangeAsksAndDeletesNothing() {
        IntentResult result = service.cancelWithin(null, null);

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("指定時間段", "非固定行程", "一個一個");
        verify(scheduleService, never()).cancelSchedule(any());
    }

    @Test
    void deletesOnlyOneTimeSchedulesInRangeAndReportsProtectedRecurring() {
        ScheduleItem inRange = proposed("臨時會議", "2026-07-21T10:00:00+08:00");
        ScheduleItem outOfRange = proposed("下下週聚餐", "2026-07-28T18:00:00+08:00");
        ScheduleItem recurring = proposed("上班日通勤與上班", "2026-07-21T07:00:00+08:00");
        recurring.repeat(ScheduleItem.Recurrence.WEEKDAYS, NOW);
        when(scheduleService.listSchedules(null))
                .thenReturn(List.of(inRange, outOfRange, recurring));

        IntentResult result = service.cancelWithin(FROM, TO);

        // 未持久化的 id 都是 null,用次數+訊息內容驗證挑選結果
        verify(scheduleService, org.mockito.Mockito.times(1)).cancelSchedule(any());
        assertThat(result.message())
                .contains("已刪除", "「臨時會議」")
                .doesNotContain("下下週聚餐")
                .contains("固定行程", "「上班日通勤與上班」", "我沒有動");
    }

    @Test
    void emptyRangeReportsNothingTouched() {
        when(scheduleService.listSchedules(null)).thenReturn(List.of());

        IntentResult result = service.cancelWithin(FROM, TO);

        assertThat(result.message()).contains("什麼都沒動");
        verify(scheduleService, never()).cancelSchedule(any());
    }

    @Test
    void privatePreviewExcludesWorkFamilyUnknownAndRecurringWithoutDeleting() {
        ScheduleItem personal = proposed("健身", "2026-07-21T10:00:00+08:00");
        personal.categorize(ScheduleItem.Category.PERSONAL, NOW);
        ScheduleItem work = proposed("公司會議", "2026-07-21T11:00:00+08:00");
        work.categorize(ScheduleItem.Category.WORK, NOW);
        ScheduleItem family = proposed("小孩回診", "2026-07-21T12:00:00+08:00");
        family.categorize(ScheduleItem.Category.FAMILY, NOW);
        ScheduleItem unknown = proposed("舊資料", "2026-07-21T13:00:00+08:00");
        ScheduleItem recurring = proposed("固定運動", "2026-07-21T14:00:00+08:00");
        recurring.categorize(ScheduleItem.Category.PERSONAL, NOW);
        recurring.repeat(ScheduleItem.Recurrence.WEEKLY, NOW);
        when(scheduleService.listSchedules(null))
                .thenReturn(List.of(personal, work, family, unknown, recurring));

        IntentResult result = service.previewPrivateWithin(FROM, TO);

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CANCELLATION_PREVIEWED);
        assertThat(result.message()).contains("健身", "公司會議", "小孩回診", "舊資料", "固定運動",
                "目前尚未刪除", "確認刪除剛才清單");
        verify(contextService).rememberScheduleList(List.of(personal));
        verify(scheduleService, never()).cancelSchedule(any());
    }

    private ScheduleItem proposed(String title, String start) {
        Instant startAt = Instant.parse(start);
        return ScheduleItem.propose(title, startAt, startAt.plusSeconds(3600), null, NOW);
    }
}
