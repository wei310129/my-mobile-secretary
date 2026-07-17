package com.aproject.internal.aidispatcher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-dispatcher.trigger-polling")
public record TriggerPollingProperties(int pageSize, int maxPagesPerPoll) {

    public TriggerPollingProperties {
        if (pageSize <= 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
        if (maxPagesPerPoll <= 0 || maxPagesPerPoll > 100) {
            throw new IllegalArgumentException("maxPagesPerPoll must be between 1 and 100");
        }
    }
}
