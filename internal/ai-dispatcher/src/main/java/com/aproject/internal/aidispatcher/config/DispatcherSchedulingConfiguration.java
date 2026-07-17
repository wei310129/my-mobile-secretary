package com.aproject.internal.aidispatcher.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "ai-dispatcher", name = "enabled", havingValue = "true")
public class DispatcherSchedulingConfiguration {
}
