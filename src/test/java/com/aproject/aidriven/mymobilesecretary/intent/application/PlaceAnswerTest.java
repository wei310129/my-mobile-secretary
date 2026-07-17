package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlaceAnswerTest {

    @Test
    void locationWithoutAddressNeverExposesCoordinates() {
        Place place = Place.create("滬江幼兒園", null, 24.98967, 121.53958,
                "學校", Instant.parse("2026-07-17T00:00:00Z"));

        IntentResult result = IntentResult.placeInfo(place,
                "彩虹門位於景美便堤後門，是開車接送入口。");

        assertThat(result.message())
                .contains("我知道").contains("附近地標").contains("景美便堤")
                .contains("Google Maps")
                .doesNotContain("24.98967", "121.53958", "座標");
    }
}
