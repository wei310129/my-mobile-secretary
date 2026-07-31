package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuotedPlaceCorrectionConversationServiceTest {

    @Test
    void explicitAddressUpdatesOnlyTheTypedQuotedPlace() {
        PlaceService places = mock(PlaceService.class);
        Place before = Place.create(
                "測試門市", "舊地址", 24.9, 121.5, "商店", Instant.EPOCH);
        Place updated = Place.create(
                "測試門市", "新北市測試區安全路88號", 24.91, 121.51, "商店", Instant.EPOCH);
        when(places.getPlace(31L)).thenReturn(before);
        when(places.updateAddress(31L, "新北市測試區安全路88號")).thenReturn(updated);
        AtomicInteger mutationBoundary = new AtomicInteger();
        QuotedPlaceCorrectionConversationService service =
                new QuotedPlaceCorrectionConversationService(places);

        IntentResult result = service.answer(
                "地址是新北市測試區安全路88號",
                "【LINE 明確引用】先前的地點回覆\n"
                        + "【LINE 引用參考】PLACE:31:1\n"
                        + "【使用者目前訊息】地址是新北市測試區安全路88號",
                mutationBoundary::incrementAndGet).orElseThrow();

        assertThat(mutationBoundary).hasValue(1);
        verify(places).updateAddress(31L, "新北市測試區安全路88號");
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_UPDATED);
        assertThat(result.message()).contains("測試門市", "安全路88號")
                .doesNotContain("PLACE:31", "UUID");
    }

    @Test
    void addressWithoutTypedQuoteCannotSelectARecentPlace() {
        PlaceService places = mock(PlaceService.class);
        QuotedPlaceCorrectionConversationService service =
                new QuotedPlaceCorrectionConversationService(places);

        assertThat(service.answer(
                "地址是新北市測試區安全路88號",
                "【近期對話】助理：某個地點\n【使用者目前訊息】地址是新北市測試區安全路88號",
                () -> {
                    throw new AssertionError("must not mutate");
                })).isEmpty();

        verify(places, never()).updateAddress(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
