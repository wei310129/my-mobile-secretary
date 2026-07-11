package com.aproject.aidriven.mymobilesecretary.geo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * 球面距離計算測試:用已知地標距離驗證。
 */
class GeoDistanceTest {

    @Test
    void samePointIsZero() {
        assertThat(GeoDistance.metersBetween(25.0330, 121.5654, 25.0330, 121.5654)).isZero();
    }

    /** 台北 101 → 高雄 85 大樓,直線約 297 公里(±3 公里容忍)。 */
    @Test
    void taipeiToKaohsiungIsAbout297Km() {
        double meters = GeoDistance.metersBetween(25.0330, 121.5654, 22.6120, 120.3000);

        assertThat(meters).isCloseTo(297_000, within(3_000.0));
    }

    /** 緯度 0.001 度 ≈ 111 公尺(geofence 測試座標的依據)。 */
    @Test
    void smallLatitudeDeltaIsAbout111Meters() {
        double meters = GeoDistance.metersBetween(25.0000, 121.5000, 25.0010, 121.5000);

        assertThat(meters).isCloseTo(111, within(2.0));
    }
}
