package com.aproject.internal.aidispatcher.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    Clock dispatcherClock() {
        return Clock.systemUTC();
    }
}
