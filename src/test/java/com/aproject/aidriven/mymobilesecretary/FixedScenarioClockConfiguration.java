package com.aproject.aidriven.mymobilesecretary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Pins only date-sensitive integration scenarios to their July 2026 fixture timeline. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedScenarioClockConfiguration {

    @Bean
    @Primary
    Clock scenarioClock() {
        return Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneId.of("Asia/Taipei"));
    }
}
