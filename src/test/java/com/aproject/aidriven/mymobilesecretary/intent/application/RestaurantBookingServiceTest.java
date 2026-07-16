package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient.RestaurantCandidate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 裁決 #47:訂餐廳不可只說做不到,要引導補資訊、查營業與菜單、給訂位建議,結尾祝用餐愉快。 */
@ExtendWith(MockitoExtension.class)
class RestaurantBookingServiceTest {

    @Mock
    private GooglePlacesClient placesClient;

    private RestaurantBookingService service;

    @BeforeEach
    void setUp() {
        service = new RestaurantBookingService(placesClient);
    }

    @Test
    void bareBookingRequestAsksAllGuidingQuestionsAtOnce() {
        IntentResult result = service.handle("幫我訂餐廳", booking(null, null, null, null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.RESTAURANT_BOOKING_INFO);
        assertThat(result.message())
                .contains("哪種料理", "什麼時候用餐", "幾位", "毛小孩")
                .doesNotContain("做不到");
    }

    @Test
    void vagueDiningTimeStillCountsAsMissing() {
        // 「週末」是模糊時段:就算 LLM 猜了 startAt 也要回問
        IntentResult result = service.handle("週末幫我訂鼎泰豐,4個人",
                booking("鼎泰豐", "2026-07-18T18:00:00+08:00", 4, null));

        assertThat(result.message()).contains("什麼時候用餐");
    }

    @Test
    void completeRequestDeliversBriefingWithMenuOpeningAndWellWishes() {
        when(placesClient.usable()).thenReturn(true);
        when(placesClient.searchRestaurantFirst("鼎泰豐")).thenReturn(Optional.of(
                new RestaurantCandidate("鼎泰豐 信義店", "台北市大安區信義路二段194號",
                        25.03, 121.53, "https://www.dintaifung.com.tw",
                        "https://maps.google.com/?cid=123", "02 2321 8928",
                        List.of("星期五: 11:00 – 20:30", "星期六: 10:30 – 21:00"),
                        true, null, true, true)));

        // 2026-07-17 是星期五
        IntentResult result = service.handle("週五晚上七點訂鼎泰豐,4大1小,有寶寶",
                booking("鼎泰豐", "2026-07-17T19:00:00+08:00", 5, "有寶寶同行"));

        assertThat(result.message())
                .contains("鼎泰豐 信義店", "02 2321 8928", "有收訂位")
                .contains("星期五: 11:00 – 20:30")
                .contains("菜單/官網｜https://www.dintaifung.com.tw")
                .contains("適合帶小朋友")
                .contains("提前 10 分鐘報到", "1.5 小時")
                .contains("祝您用餐愉快");
        // 沒提到毛小孩就不要多話
        assertThat(result.message()).doesNotContain("寵物");
    }

    @Test
    void unknownHospitalityFieldIsAskedNotAssumed() {
        when(placesClient.usable()).thenReturn(true);
        when(placesClient.searchRestaurantFirst("巷口熱炒")).thenReturn(Optional.of(
                new RestaurantCandidate("巷口熱炒", "新北市新店區某路1號", 24.9, 121.5,
                        null, "https://maps.google.com/?cid=456", null,
                        List.of(), null, null, null, null)));

        IntentResult result = service.handle("明天晚上七點訂巷口熱炒,6位,帶輪椅的長輩",
                booking("巷口熱炒", "2026-07-17T19:00:00+08:00", 6, "有坐輪椅的長輩"));

        assertThat(result.message())
                .contains("記得確認無障礙動線")
                .contains("Google Maps 的照片裡通常找得到菜單");
    }

    @Test
    void placesFailureDegradesGracefullyWithoutBreakingTheFlow() {
        when(placesClient.usable()).thenReturn(true);
        when(placesClient.searchRestaurantFirst("鼎泰豐"))
                .thenThrow(new IntegrationException("boom", null));

        IntentResult result = service.handle("明天晚上七點訂鼎泰豐,4位",
                booking("鼎泰豐", "2026-07-17T19:00:00+08:00", 4, null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.RESTAURANT_BOOKING_INFO);
        assertThat(result.message()).contains("建議直接致電餐廳", "祝您用餐愉快");
    }

    private static IntentCommand booking(String restaurant, String startAt,
                                         Integer partySize, String needs) {
        IntentOptions options = new IntentOptions(null, null, null, null, null, null, null,
                null, null, partySize, null, null, null, null, null, null, null, null, null,
                null, null, needs);
        return new IntentCommand(IntentCommand.Type.BOOK_RESTAURANT, null, null, startAt, null,
                restaurant, null, null, null, null, null, null, null, options);
    }
}
