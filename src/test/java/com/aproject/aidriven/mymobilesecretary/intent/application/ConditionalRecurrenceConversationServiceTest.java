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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void holidaySkipIsRecognizedButMissingDurationStillDoesNotMutate() {
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "每週三七點上英文課做到年底，國定假日不用上，補課時間老師會另外說",
                mutations::incrementAndGet).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("國定假日採跳過", "補課不會自動建立", "每次持續多久", "上午或晚上");
        assertThat(mutations).hasValue(0);
    }

    @Test
    void durationFollowUpResumesPendingHolidaySkipAndCreatesOnlyDraft() {
        ConditionalRecurrenceRule saved = ConditionalRecurrenceRule.draft(
                "英文課", Instant.parse("2026-07-22T11:00:00Z"),
                Instant.parse("2026-07-22T12:00:00Z"), LocalDate.of(2026, 12, 31),
                ConditionalRecurrenceRule.HolidayPolicy.SKIP,
                ConditionalRecurrenceRule.ClosurePolicy.NONE, null,
                Instant.parse("2026-07-18T00:00:00Z"));
        ReflectionTestUtils.setField(saved, "id", 13L);
        when(recurrenceService.createDraft(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(saved);
        ConversationSnapshot previous = new ConversationSnapshot(
                null, null, null, java.util.List.of(), java.util.List.of(),
                "CLARIFICATION_NEEDED",
                "每週三七點上英文課做到年底，國定假日不用上，補課時間老師會另外說",
                "我已辨識這是條件式週期，還需要確認：每次持續多久");
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service().answer(
                "每次一小時，是晚上七點", previous, mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
        assertThat(result.message())
                .contains("草稿 #13", "確認為國定假日就跳過", "補課", "不自動建立");
        verify(recurrenceService).createDraft(
                org.mockito.ArgumentMatchers.eq("英文課"),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-07-22T11:00:00Z")),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-07-22T12:00:00Z")),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 12, 31)),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.HolidayPolicy.SKIP),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.ClosurePolicy.NONE),
                org.mockito.ArgumentMatchers.isNull());
    }

    @ParameterizedTest
    @CsvSource({
        "凌晨七點,2026-07-21T23:00:00Z",
        "上午七點,2026-07-21T23:00:00Z",
        "早上七點,2026-07-21T23:00:00Z",
        "下午七點,2026-07-22T11:00:00Z",
        "晚上七點,2026-07-22T11:00:00Z",
        "黃昏七點,2026-07-22T11:00:00Z",
        "傍晚七點,2026-07-22T11:00:00Z",
        "中午十一點半,2026-07-22T03:30:00Z",
        "中午十二點半,2026-07-22T04:30:00Z",
        "中午一點,2026-07-22T05:00:00Z"
    })
    void understandsAllApprovedTimePeriodAliases(String spokenTime, Instant expectedStart) {
        when(recurrenceService.createDraft(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    ConditionalRecurrenceRule saved = ConditionalRecurrenceRule.draft(
                            call.getArgument(0), call.getArgument(1), call.getArgument(2),
                            call.getArgument(3), call.getArgument(4), call.getArgument(5),
                            call.getArgument(6), Instant.parse("2026-07-18T00:00:00Z"));
                    ReflectionTestUtils.setField(saved, "id", 14L);
                    return saved;
                });

        IntentResult result = service().answer(
                "每週三%s上英文課一小時做到年底，國定假日不用上".formatted(spokenTime),
                () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        verify(recurrenceService).createDraft(
                org.mockito.ArgumentMatchers.eq("英文課"), start.capture(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.HolidayPolicy.SKIP),
                org.mockito.ArgumentMatchers.eq(ConditionalRecurrenceRule.ClosurePolicy.NONE),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(start.getValue()).isEqualTo(expectedStart);
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
