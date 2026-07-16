package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 裁決 #48:「你自己看著辦」只能低風險安排(照原時間確認),且必須回報做了什麼。 */
@ExtendWith(MockitoExtension.class)
class DelegatedDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");

    @Mock
    private ConversationContextService contextService;
    @Mock
    private ScheduleService scheduleService;

    private DelegatedDecisionService service;

    @BeforeEach
    void setUp() {
        service = new DelegatedDecisionService(contextService, scheduleService);
    }

    @Test
    void proposedScheduleIsConfirmedAtOriginalTimeAndReported() {
        ScheduleItem proposed = ScheduleItem.propose("簡報排練",
                Instant.parse("2026-07-17T14:00:00+08:00"),
                Instant.parse("2026-07-17T15:00:00+08:00"), null, NOW);
        when(contextService.scheduleIdAt(null)).thenReturn(10L);
        when(scheduleService.getSchedule(10L)).thenReturn(proposed);

        IntentResult result = service.decide();

        verify(scheduleService).confirmSchedule(10L);
        assertThat(result.message())
                .contains("最低風險", "照原時間 07/17 14:00 確認了「簡報排練」")
                .contains("沒有動其他行程", "想改時間或取消");
    }

    @Test
    void nothingPendingReportsNoActionTaken() {
        when(contextService.scheduleIdAt(null)).thenReturn(null);

        IntentResult result = service.decide();

        verify(scheduleService, never()).confirmSchedule(any());
        assertThat(result.message()).contains("什麼都沒動");
    }

    @Test
    void alreadyConfirmedScheduleIsLeftUntouched() {
        ScheduleItem confirmed = ScheduleItem.propose("週會",
                Instant.parse("2026-07-17T10:00:00+08:00"),
                Instant.parse("2026-07-17T11:00:00+08:00"), null, NOW);
        confirmed.confirm(NOW);
        when(contextService.scheduleIdAt(null)).thenReturn(7L);
        when(scheduleService.getSchedule(7L)).thenReturn(confirmed);

        IntentResult result = service.decide();

        verify(scheduleService, never()).confirmSchedule(any());
        assertThat(result.message()).contains("什麼都沒動");
    }
}
