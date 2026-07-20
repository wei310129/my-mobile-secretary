package com.aproject.aidriven.mymobilesecretary.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChineseTimePeriodTest {

    @ParameterizedTest
    @CsvSource({
        "凌晨,7,7", "上午,7,7", "早上,7,7",
        "下午,7,19", "晚上,7,19", "黃昏,7,19", "傍晚,7,19",
        "凌晨,12,0", "上午,12,0", "早上,12,0",
        "下午,12,12", "晚上,12,12", "黃昏,12,12", "傍晚,12,12",
        "中午,11,11", "中午,12,12", "中午,1,13"
    })
    void mapsSpokenPeriodToTwentyFourHour(String period, int hour, int expected) {
        assertThat(ChineseTimePeriod.toTwentyFourHour(period, hour)).isEqualTo(expected);
    }
}
