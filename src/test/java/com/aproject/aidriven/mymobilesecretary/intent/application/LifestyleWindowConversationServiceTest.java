package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.LifestyleWindowService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LifestyleWindowConversationServiceTest {

    @Mock
    private LifestyleWindowService windowService;

    @Test
    void parsesNaturalChineseMealAndSleepWindowsWithPeriodInference() {
        var parsed = LifestyleWindowConversationService.parse(
                "平日早餐7點到7點半、午餐12點到1點、晚餐6點半到7點半、睡覺11點到7點");

        assertThat(parsed).containsExactly(
                new LifestyleWindowConversationService.ParsedWindow(
                        LifestyleWindow.Kind.BREAKFAST, LocalTime.of(7, 0), LocalTime.of(7, 30)),
                new LifestyleWindowConversationService.ParsedWindow(
                        LifestyleWindow.Kind.LUNCH, LocalTime.NOON, LocalTime.of(13, 0)),
                new LifestyleWindowConversationService.ParsedWindow(
                        LifestyleWindow.Kind.DINNER, LocalTime.of(18, 30), LocalTime.of(19, 30)),
                new LifestyleWindowConversationService.ParsedWindow(
                        LifestyleWindow.Kind.SLEEP, LocalTime.of(23, 0), LocalTime.of(7, 0)));
    }

    @Test
    void savesEveryWeekdayWindowOnlyAfterMutationBoundary() {
        LifestyleWindowConversationService service =
                new LifestyleWindowConversationService(windowService);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service.answer(
                "我平日通常早餐7點到7點半，午餐12點到1點，晚餐晚上6點半到7點半，睡覺11點到7點",
                mutations::incrementAndGet).orElseThrow();

        assertThat(mutations).hasValue(1);
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLANNING_PREFERENCE_SET);
        assertThat(result.message()).contains("不建立固定行程", "之後新行程若壓縮", "讓你決定");
        verify(windowService).set(LifestyleWindow.DayType.WEEKDAY,
                LifestyleWindow.Kind.BREAKFAST, LocalTime.of(7, 0), LocalTime.of(7, 30));
        verify(windowService).set(LifestyleWindow.DayType.WEEKDAY,
                LifestyleWindow.Kind.LUNCH, LocalTime.NOON, LocalTime.of(13, 0));
        verify(windowService).set(LifestyleWindow.DayType.WEEKDAY,
                LifestyleWindow.Kind.DINNER, LocalTime.of(18, 30), LocalTime.of(19, 30));
        verify(windowService).set(LifestyleWindow.DayType.WEEKDAY,
                LifestyleWindow.Kind.SLEEP, LocalTime.of(23, 0), LocalTime.of(7, 0));
    }

    @Test
    void asksWhetherWindowsAreWeekdayOrHolidayBeforeSaving() {
        LifestyleWindowConversationService service =
                new LifestyleWindowConversationService(windowService);
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service.answer(
                "幫我設定午餐12點到1點", mutations::incrementAndGet).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("平日", "假日", "確認前不會保存");
        assertThat(mutations).hasValue(0);
        verify(windowService, never()).set(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ordinaryMealAvailabilityQuestionPassesThrough() {
        LifestyleWindowConversationService service =
                new LifestyleWindowConversationService(windowService);

        Optional<IntentResult> result = service.answer("午餐前有空嗎", () -> { });

        assertThat(result).isEmpty();
    }
}
