package com.aproject.aidriven.mymobilesecretary.intent.application;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 覆寫 Spring AI 的 AnthropicChatModel 自動配置。
 *
 * 原因:Spring AI 預設把 temperature=0.8 焊進 defaultOptions,
 * 但 Claude 4.7+/Opus 4.8 家族已移除 sampling 參數——只要請求帶
 * temperature(不論值多少)一律 400。YAML 覆寫不了這個焊死的預設值,
 * 只能自建 bean、把 temperature 清成 null 才不會被序列化進請求。
 */
@Configuration
public class AnthropicChatConfig {

    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicApi anthropicApi,
                                                 AnthropicChatProperties chatProperties,
                                                 RetryTemplate retryTemplate,
                                                 ToolCallingManager toolCallingManager,
                                                 ObjectProvider<ObservationRegistry> observationRegistry) {
        AnthropicChatOptions options = chatProperties.getOptions().copy();
        options.setTemperature(null);

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .toolExecutionEligibilityPredicate(new DefaultToolExecutionEligibilityPredicate())
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }
}
