package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.Entry;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.Payload;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 收據編排的可靠度測試:LLM 失敗有明確回覆、壞行跳過好行照存、
 * 非收據照片不入庫——任何情況都不能往 webhook 拋例外。
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    /** 2026-07-14 10:00 台北時間。 */
    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final byte[] IMAGE = "fake".getBytes();

    @Mock
    private PriceRecordService priceRecordService;

    private ReceiptService service(ReceiptInterpreter interpreter) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReceiptInterpreter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(interpreter);
        return new ReceiptService(provider, priceRecordService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void interpreterDisabledRepliesGuidance() {
        ReceiptService.ReceiptResult result = service(null).handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isZero();
        assertThat(result.message()).contains("未啟用");
        verify(priceRecordService, never()).record(anyString(), any(), anyInt(), any());
    }

    /** LLM 炸掉 → 明確請使用者重拍/改文字,不往外拋。 */
    @Test
    void interpreterFailureRepliesGracefully() {
        ReceiptService.ReceiptResult result = service((bytes, mime) -> {
            throw new IllegalStateException("LLM down");
        }).handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isZero();
        assertThat(result.message()).contains("解析不了");
    }

    @Test
    void nonReceiptPhotoSavesNothing() {
        ReceiptService.ReceiptResult result = service((bytes, mime) ->
                new ReceiptCommand(null, null, List.of())).handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isZero();
        assertThat(result.message()).contains("不是收據");
    }

    /** 壞行(無名、價格 0、null)跳過,好行照存;日期照收據上的。 */
    @Test
    void invalidLinesAreSkippedValidLinesSaved() {
        ReceiptCommand command = new ReceiptCommand("全聯", "2026-07-12", Arrays.asList(
                new ReceiptCommand.Line("鮮奶", 95, 1),
                new ReceiptCommand.Line("", 30, 1),
                new ReceiptCommand.Line("排骨", null, 1),
                new ReceiptCommand.Line("衛生紙", 0, 1),
                null,
                new ReceiptCommand.Line("雞蛋", 75, 1)));

        ReceiptService.ReceiptResult result =
                service((bytes, mime) -> command).handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isEqualTo(2);
        assertThat(result.message()).contains("鮮奶").contains("雞蛋").contains("全聯");
        verify(priceRecordService).record("鮮奶", "全聯", 95, LocalDate.parse("2026-07-12"));
        verify(priceRecordService).record("雞蛋", "全聯", 75, LocalDate.parse("2026-07-12"));
    }

    /** 收據沒印日期(或格式爛)→ 當作今天(台北時間)。 */
    @Test
    void missingDateFallsBackToToday() {
        ReceiptCommand command = new ReceiptCommand(null, "112/07/12", List.of(
                new ReceiptCommand.Line("鮮奶", 95, 1)));

        service((bytes, mime) -> command).handleImage(IMAGE, "image/jpeg");

        verify(priceRecordService).record("鮮奶", null, 95, LocalDate.parse("2026-07-14"));
    }

    @Test
    void itineraryImageCreatesPreviewDraftWithoutSavingPrices() {
        ReceiptCommand command = new ReceiptCommand(null, null, List.of(),
                ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "測試郵輪行程",
                List.of(new ReceiptCommand.ItineraryEntry(
                        "11-18", "08:00", "09:00", "下船", "那霸港", null)),
                List.of("岸上活動"), List.of("攜帶護照"));
        TravelItineraryDraftService draftService = mock(TravelItineraryDraftService.class);
        TravelItineraryDraftAnswerService answerService = mock(
                TravelItineraryDraftAnswerService.class);
        DraftView draft = new DraftView(1L, "測試郵輪行程", Status.PENDING,
                new Payload(List.of(new Entry(
                        "11-18", "08:00", "09:00", "下船", "那霸港", null)),
                        List.of("岸上活動"), List.of("攜帶護照")), NOW.plusSeconds(3600));
        when(draftService.create(command)).thenReturn(draft);
        when(answerService.previewMessage(draft)).thenReturn("行程草稿預覽");
        ReceiptService receiptService = service((bytes, mime) -> command);
        receiptService.setTravelItineraryServices(draftService, answerService);

        ReceiptService.ReceiptResult result = receiptService.handleImage(IMAGE, "image/jpeg");

        assertThat(result.action()).isEqualTo("TRAVEL_ITINERARY_DRAFTED");
        assertThat(result.savedCount()).isZero();
        assertThat(result.message()).contains("行程草稿預覽");
        verify(priceRecordService, never()).record(anyString(), any(), anyInt(), any());
    }
}
