package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.application.ConditionalRecurrenceService;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConditionalRecurrenceConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));

    @Mock
    private ConditionalRecurrenceService recurrenceService;

    @Test
    void incompleteConditionalWeeklyRequestAsksAllMissingFieldsWithoutMutation() {
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "週五十點開週會，若放假就改週四，颱風停班就順延到下個上班日",
                mutations::incrementAndGet).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("條件式週期", "不會建立成普通每週固定行程", "每次持續多久", "停班停課適用縣市");
        assertThat(mutations).hasValue(0);
    }

    @Test
    void completeRequestCreatesDraftInsteadOfWeeklySchedule() {
        ConditionalRecurrenceRule saved = ConditionalRecurrenceRule.draft(
                "週會", Instant.parse("2026-07-24T02:00:00Z"),
                Instant.parse("2026-07-24T03:00:00Z"), LocalDate.of(2026, 12, 31),
                ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY,
                ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY,
                "臺北市", Instant.parse("2026-07-18T00:00:00Z"));
        ReflectionTestUtils.setField(saved, "id", 12L);
        when(recurrenceService.createDraft(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(saved);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "每週五上午十點開週會一小時做到年底，國定假日就提前，臺北市颱風停班就順延到下個上班日",
                mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
        assertThat(result.message()).contains("草稿 #12", "尚未啟用", "不是普通固定行程", "啟用條件規則 12");
        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        verify(recurrenceService).createDraft(
                org.mockito.ArgumentMatchers.eq("週會"), start.capture(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-07-24T03:00:00Z")),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 12, 31)),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY),
                org.mockito.ArgumentMatchers.eq("臺北市"));
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-07-24T02:00:00Z"));
    }

    @Test
    void holidaySkipIsNotSilentlyChangedIntoPreviousBusinessDay() {
        IntentResult result = service().answer(
                "每週三晚上七點上英文課一小時做到年底，國定假日不用上",
                () -> { }).orElseThrow();

        assertThat(result.message()).contains("假日要跳過、前移或另行補課", "不能把「不用上」猜成前移");
    }

    @Test
    void explicitActivationUsesRuleIdAndMutationBoundary() {
        ConditionalRecurrenceRule rule = ConditionalRecurrenceRule.draft(
                "週會", Instant.parse("2026-07-24T02:00:00Z"),
                Instant.parse("2026-07-24T03:00:00Z"), null,
                ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY,
                ConditionalRecurrenceRule.ClosurePolicy.NONE, null,
                Instant.parse("2026-07-18T00:00:00Z"));
        ReflectionTestUtils.setField(rule, "id", 12L);
        rule.activate(Instant.parse("2026-07-18T00:00:00Z"));
        when(recurrenceService.activate(12L)).thenReturn(rule);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "確認啟用條件規則12", mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_RECURRENCE_SET);
        assertThat(result.message()).contains("已啟用", "官方狀態", "尚未確認時不建立");
    }

    private ConditionalRecurrenceConversationService service() {
        return new ConditionalRecurrenceConversationService(recurrenceService, CLOCK);
    }
}
