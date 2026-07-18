package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoundedFreeSlotConversationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final Instant FROM = Instant.parse("2026-07-19T05:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-07-19T09:00:00Z");

    @Mock
    private FreeSlotService freeSlotService;

    @Test
    void returnsAtMostRequestedTwoSlotsInsideExactRangeWithoutMutation() {
        when(freeSlotService.suggest(FROM, UNTIL, Duration.ofHours(2), null)).thenReturn(List.of(
                new FreeSlotService.Slot(FROM, FROM.plus(Duration.ofHours(2))),
                new FreeSlotService.Slot(FROM.plus(Duration.ofHours(2)), UNTIL),
                new FreeSlotService.Slot(FROM.plus(Duration.ofMinutes(30)),
                        FROM.plus(Duration.ofMinutes(150)))));

        IntentResult result = service().answer(
                "幫我看明天下午一點到五點有沒有連續兩小時空檔，先給我兩個選項，我還沒決定要排什麼")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FREE_SLOTS_SUGGESTED);
        assertThat(result.message()).contains("13:00–15:00", "15:00–17:00", "只提供空檔", "沒有建立或修改資料")
                .doesNotContain("13:30–15:30");
        verify(freeSlotService).suggest(FROM, UNTIL, Duration.ofHours(2), null);
    }

    @Test
    void noSuitableSlotIsAnHonestReadOnlyAnswer() {
        when(freeSlotService.suggest(FROM, UNTIL, Duration.ofHours(2), null)).thenReturn(List.of());

        IntentResult result = service().answer(
                "明天下午一點到五點有沒有連續兩小時空檔，先給我兩個選項，還沒決定")
                .orElseThrow();

        assertThat(result.message()).contains("沒有連續 2 小時", "只有查詢", "沒有建立或修改資料");
    }

    @Test
    void creationRequestAndUnboundedQuestionStayOnGeneralPath() {
        assertThat(service().answer("明天下午一點到五點幫我排兩小時運動")).isEmpty();
        assertThat(service().answer("明天有沒有空檔，我還沒決定")).isEmpty();
    }

    private BoundedFreeSlotConversationService service() {
        return new BoundedFreeSlotConversationService(freeSlotService, CLOCK);
    }
}
