package com.aproject.internal.aidispatcher.trigger.source;

import com.aproject.internal.aidispatcher.config.MainFeedProperties;
import com.aproject.internal.aidispatcher.trigger.domain.DevelopmentTriggerEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(
        prefix = "ai-dispatcher.main-feed", name = "enabled", havingValue = "true")
public class MainApplicationFeedSource implements TriggerSource {

    public static final String SOURCE_KEY = "main-conversation-feed-v1";
    private static final String FEED_PATH = "/internal/integration/v1/development-events";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String bearerToken;

    public MainApplicationFeedSource(RestClient.Builder restClientBuilder,
                                     ObjectMapper objectMapper,
                                     MainFeedProperties properties) {
        this(buildClient(restClientBuilder, properties), objectMapper, properties.bearerToken());
    }

    MainApplicationFeedSource(RestClient restClient,
                              ObjectMapper objectMapper,
                              String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException(
                    "AI_DISPATCHER_MAIN_FEED_TOKEN is required when the main feed is enabled");
        }
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.bearerToken = bearerToken.strip();
    }

    private static RestClient buildClient(RestClient.Builder restClientBuilder,
                                          MainFeedProperties properties) {
        if (properties.bearerToken().isBlank()) {
            throw new IllegalArgumentException(
                    "AI_DISPATCHER_MAIN_FEED_TOKEN is required when the main feed is enabled");
        }
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public TriggerSourcePage fetchAfter(String cursor, int limit) {
        FeedResponse response = restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(FEED_PATH).queryParam("limit", limit);
                    if (cursor != null) {
                        builder.queryParam("after", cursor);
                    }
                    return builder.build();
                })
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve()
                .body(FeedResponse.class);
        if (response == null) {
            throw new IllegalStateException("Main application feed returned an empty response");
        }
        if (response.nextCursor() == null || response.nextCursor().isBlank()) {
            throw new IllegalStateException("Main application feed did not return nextCursor");
        }
        List<DevelopmentTriggerEvent> mapped = response.events() == null
                ? List.of()
                : response.events().stream().map(this::mapEvent).toList();
        return new TriggerSourcePage(response.nextCursor(), response.hasMore(), mapped);
    }

    private DevelopmentTriggerEvent mapEvent(FeedEvent event) {
        if (event == null) {
            throw new IllegalStateException("Main application feed contains a null event");
        }
        try {
            String metadata = objectMapper.writeValueAsString(
                    event.metadata() == null ? objectMapper.createObjectNode() : event.metadata());
            return new DevelopmentTriggerEvent(
                    event.eventId(), event.type(), event.subjectRef(),
                    event.schemaVersion(), event.occurredAt(), metadata);
        } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Could not serialize trigger metadata", serializationFailure);
        }
    }

    record FeedResponse(List<FeedEvent> events, String nextCursor, boolean hasMore) {
    }

    record FeedEvent(
            String eventId,
            String type,
            Instant occurredAt,
            String subjectRef,
            int schemaVersion,
            JsonNode metadata
    ) {
    }
}
