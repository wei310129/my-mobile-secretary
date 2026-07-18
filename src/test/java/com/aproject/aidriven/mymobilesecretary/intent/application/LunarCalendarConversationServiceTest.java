package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Candidate;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Conversion;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LeapMonth;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LunarDate;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LunarCalendarConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final String SOURCE = "中央氣象署曆象資料 2026 v1";

    @Test
    void unrelatedTextPassesThrough() {
        LunarCalendarConversationService service = service(List.of());

        assertThat(service.answer("明天下午三點開會")).isEmpty();
    }

    @Test
    void missingYearIsClarifiedBeforeProviderOrModelRuns() {
        LunarCalendarConversationService service = service(List.of(date -> {
            throw new AssertionError("provider must not run without a year");
        }));

        IntentResult result = service.answer("農曆八月十五晚上跟家人吃飯").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("還缺年份", "同一個農曆月日每年", "不會建立或修改");
    }

    @Test
    void missingVerifiedProviderNeverGuessesOrMutates() {
        LunarCalendarConversationService service = service(List.of());

        IntentResult result = service.answer("今年農曆八月十五幫我加聚餐").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("沒有安裝可追溯來源", "不能安全換算", "不會建立或修改", "不會用固定天數或模型猜測");
    }

    @Test
    void verifiedReadOnlyConversionShowsGregorianWeekdayAndSource() {
        LunarCalendarConversionProvider provider = date -> Conversion.resolved(
                LocalDate.of(2026, 9, 25), LeapMonth.REGULAR, SOURCE);
        LunarCalendarConversationService service = service(List.of(provider));

        IntentResult result = service.answer("今年農曆八月十五是國曆哪天？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.SOCIAL_REPLIED);
        assertThat(result.message())
                .contains("2026 農曆年", "國曆 2026/09/25（五）", SOURCE,
                        "只回覆換算結果", "不會建立或修改");
    }

    @Test
    void scheduleRequestRequiresExplicitGregorianConfirmation() {
        LunarCalendarConversionProvider provider = date -> Conversion.resolved(
                LocalDate.of(2026, 9, 25), LeapMonth.REGULAR, SOURCE);
        LunarCalendarConversationService service = service(List.of(provider));

        IntentResult result = service.answer("今年農曆八月十五晚上幫我安排家族聚餐").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("2026/09/25（五）", SOURCE, "確認國曆 2026/09/25（五）", "才能繼續建立或修改");
    }

    @Test
    void unspecifiedMonthAsksRegularOrLeapWhenProviderReturnsBoth() {
        LunarCalendarConversionProvider provider = date -> Conversion.ambiguous(List.of(
                new Candidate(LocalDate.of(2033, 8, 25), LeapMonth.REGULAR),
                new Candidate(LocalDate.of(2033, 9, 23), LeapMonth.LEAP)), SOURCE);
        LunarCalendarConversationService service = service(List.of(provider));

        IntentResult result = service.answer("2033年農曆七月初一安排祭祖").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("一般月份：2033/08/25", "閏月：2033/09/23",
                        "一般七月", "閏七月", SOURCE, "不會建立或修改");
    }

    @Test
    void explicitLeapMonthAndRocYearArePassedToProvider() {
        AtomicReference<LunarDate> received = new AtomicReference<>();
        LunarCalendarConversionProvider provider = date -> {
            received.set(date);
            return Conversion.resolved(LocalDate.of(2026, 8, 15), LeapMonth.LEAP, SOURCE);
        };
        LunarCalendarConversationService service = service(List.of(provider));

        service.answer("民國115年農曆閏六月初三是國曆哪天").orElseThrow();

        assertThat(received.get()).isEqualTo(new LunarDate(2026, 6, 3, LeapMonth.LEAP));
    }

    @Test
    void annualLunarRuleNeedsBoundedYearRange() {
        LunarCalendarConversationService service = service(List.of());

        IntentResult result = service.answer("每年農曆八月十五晚上六點聚餐").orElseThrow();

        assertThat(result.message())
                .contains("每年農曆八月十五", "不能存成固定國曆每年重複", "開始與截止年份", "不會建立行程");
    }

    @Test
    void annualLunarRuleIsExpandedYearByYearForConfirmation() {
        List<LunarDate> calls = new ArrayList<>();
        LunarCalendarConversionProvider provider = date -> {
            calls.add(date);
            return Conversion.resolved(LocalDate.of(date.year(), 9, 20 + date.year() - 2026),
                    LeapMonth.REGULAR, SOURCE);
        };
        LunarCalendarConversationService service = service(List.of(provider));

        IntentResult result = service.answer(
                "從2026年到2028年每年農曆八月十五晚上安排家族聚餐").orElseThrow();

        assertThat(calls).extracting(LunarDate::year).containsExactly(2026, 2027, 2028);
        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("2026 農曆年：2026/09/20（日）",
                        "2027 農曆年：2027/09/21（二）",
                        "2028 農曆年：2028/09/22（五）",
                        SOURCE, "尚未建立行程", "不能改成固定國曆每年重複");
    }

    @Test
    void invalidOrUnavailableConversionIsReportedWithoutInventingDate() {
        LunarCalendarConversionProvider provider = date ->
                Conversion.failed(Status.INVALID_DATE, SOURCE);
        LunarCalendarConversationService service = service(List.of(provider));

        IntentResult result = service.answer("2026年農曆閏十二月三十幫我排休假").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("沒有這個月日或閏月", SOURCE, "不會建立或修改")
                .doesNotContain("2026/01/", "2026/02/");
    }

    @Test
    void providerExceptionFailsClosed() {
        LunarCalendarConversationService service = service(List.of(date -> {
            throw new IllegalStateException("calendar backend failed");
        }));

        IntentResult result = service.answer("今年農曆八月十五幫我加行程").orElseThrow();

        assertThat(result.message()).contains("暫時無法查核", "提供者執行失敗", "不會建立或修改");
    }

    @Test
    void scenarioCatalogMetaTextDoesNotExecuteEmbeddedLunarExample() {
        LunarCalendarConversationService service = service(List.of(date -> {
            throw new AssertionError("meta discussion must pass through");
        }));

        assertThat(service.answer("情境清單測試資料：使用者說今年農曆八月十五幫我排聚餐")).isEmpty();
    }

    private static LunarCalendarConversationService service(
            List<LunarCalendarConversionProvider> providers) {
        return new LunarCalendarConversationService(providers, CLOCK);
    }
}
