package com.aproject.aidriven.mymobilesecretary.geo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 建地點的 Google 補全規則測試:自帶座標不查、缺座標查 Google 補空缺、
 * 沒金鑰/查不到/查失敗都回可行動的業務錯誤,絕不猜位置。
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");

    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private GooglePlacesClient googlePlacesClient;

    private PlaceService service;

    @BeforeEach
    void setUp() {
        service = new PlaceService(placeRepository, googlePlacesClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void saveReturnsInput() {
        when(placeRepository.save(any(Place.class))).thenAnswer(AdditionalAnswers.returnsFirstArg());
    }

    /** 自帶座標 → 照舊建立,不打 Google(不花錢)。 */
    @Test
    void explicitCoordinatesSkipGoogle() {
        saveReturnsInput();

        Place place = service.createPlace("我家", null, 24.97, 121.54, null);

        assertThat(place.getLatitude()).isEqualTo(24.97);
        verifyNoInteractions(googlePlacesClient);
    }

    /** 只給名字 → Google 補座標/地址/類型;使用者給的欄位優先。 */
    @Test
    void missingCoordinatesAreFilledFromGoogle() {
        saveReturnsInput();
        when(googlePlacesClient.usable()).thenReturn(true);
        when(googlePlacesClient.searchFirst("全聯")).thenReturn(Optional.of(
                new GooglePlacesClient.PlaceCandidate(
                        "全聯福利中心 新店民權店", "新北市新店區民權路42號", 24.9676, 121.5407, "超市")));

        Place place = service.createPlace("全聯", null, null, null, null);

        assertThat(place.getName()).isEqualTo("全聯");
        assertThat(place.getAddress()).isEqualTo("新北市新店區民權路42號");
        assertThat(place.getLatitude()).isEqualTo(24.9676);
        assertThat(place.getType()).isEqualTo("超市");
    }

    /** 使用者有給地址/類型 → 不被 Google 蓋掉,只補座標。 */
    @Test
    void userProvidedFieldsWinOverGoogle() {
        saveReturnsInput();
        when(googlePlacesClient.usable()).thenReturn(true);
        when(googlePlacesClient.searchFirst(anyString())).thenReturn(Optional.of(
                new GooglePlacesClient.PlaceCandidate(
                        "全聯", "Google的地址", 24.9676, 121.5407, "超市")));

        Place place = service.createPlace("全聯", "我習慣叫的地址", null, null, "常去的店");

        assertThat(place.getAddress()).isEqualTo("我習慣叫的地址");
        assertThat(place.getType()).isEqualTo("常去的店");
        assertThat(place.getLatitude()).isEqualTo(24.9676);
    }

    @Test
    void missingCoordinatesWithoutGoogleKeyIsRejected() {
        when(googlePlacesClient.usable()).thenReturn(false);

        assertThatThrownBy(() -> service.createPlace("全聯", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MISSING_COORDINATES");
    }

    @Test
    void googleNotFoundIsRejectedWithActionableMessage() {
        when(googlePlacesClient.usable()).thenReturn(true);
        when(googlePlacesClient.searchFirst(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPlace("不存在的店", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PLACE_NOT_FOUND_ON_GOOGLE");
    }

    /** Google 掛掉 → 明確錯誤,不讓 IntegrationException 直接洩漏成 500。 */
    @Test
    void googleFailureIsWrappedAsBusinessError() {
        when(googlePlacesClient.usable()).thenReturn(true);
        when(googlePlacesClient.searchFirst(anyString()))
                .thenThrow(new IllegalStateException("Google down"));

        assertThatThrownBy(() -> service.createPlace("全聯", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PLACE_LOOKUP_FAILED");
    }
}
