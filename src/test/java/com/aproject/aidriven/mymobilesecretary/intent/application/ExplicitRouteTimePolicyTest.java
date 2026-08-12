package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExplicitRouteTimePolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-04T04:00:00Z"), ZoneId.of("Asia/Taipei"));

    @ParameterizedTest
    @CsvSource({
        "明天早上九點從捷運大坪林到高鐵桃園站,2026-08-05T09:00+08:00",
        "幫我安排明天九點半從公司到台北車站,2026-08-05T09:30+08:00",
        "規劃明天9:45從台北車站到桃園機場,2026-08-05T09:45+08:00",
        "幫我規劃待會3:30點從公司到高鐵桃園站,2026-08-04T15:30+08:00",
        "待會三點半從公司到台北車站,2026-08-04T15:30+08:00",
        "稍後下午3:30從公司到台北車站,2026-08-04T15:30+08:00",
        "等一下15:30從公司到台北車站,2026-08-04T15:30+08:00",
        "晚點四點從公司到台北車站,2026-08-04T16:00+08:00"
    })
    void groundsCommonExplicitRouteTimesWithoutModelOutput(
            String text, String expected) {
        assertThat(ExplicitRouteTimePolicy.startAt(text, CLOCK)).contains(expected);
    }

    @Test
    void doesNotInventAContextForBareTimeOrAClockForVagueImmediateWording() {
        assertThat(ExplicitRouteTimePolicy.startAt("3:30從公司到高鐵桃園站", CLOCK)).isEmpty();
        assertThat(ExplicitRouteTimePolicy.startAt("待會從公司到高鐵桃園站", CLOCK)).isEmpty();
    }
}
