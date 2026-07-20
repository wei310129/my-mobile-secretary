package com.aproject.aidriven.mymobilesecretary.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TimeDisplayPreferenceServiceTest {

    @Test
    void formatsConversationalClockTimesWithoutChangingIsoMachineValues() {
        String result = TimeDisplayPreferenceService.toTwelveHour(
                "07:05–12:30、19:00；API=2030-08-10T19:00:00+08:00");

        assertThat(result).isEqualTo(
                "上午 7:05–下午 12:30、下午 7:00；API=2030-08-10T19:00:00+08:00");
    }
}
