package com.aproject.aidriven.mymobilesecretary.integration.line;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineWebhookPayloadTest {

    @Test
    void webhookEventIdIsPreferredForIdempotency() {
        LineWebhookPayload.Event event = new LineWebhookPayload.Event(
                "message", "reply", "webhook-123", 1L,
                new LineWebhookPayload.Message("message-456", "text", "hello"),
                new LineWebhookPayload.Source("user"));

        assertThat(event.idempotencyKey()).isEqualTo("webhook-123");
    }

    @Test
    void messageIdIsSafeFallbackForOlderWebhookFixture() {
        LineWebhookPayload.Event event = new LineWebhookPayload.Event(
                "message", "reply", null, null,
                new LineWebhookPayload.Message("message-456", "image", null),
                new LineWebhookPayload.Source("user"));

        assertThat(event.idempotencyKey()).isEqualTo("message:message-456");
    }

    @Test
    void quotedMessageIdIsRetainedFromWebhookPayload() throws Exception {
        String json = """
                {"events":[{"type":"message","message":{"id":"m2","type":"text",
                "text":"7/9","quotedMessageId":"m1","quoteToken":"token"}}]}
                """;

        LineWebhookPayload payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, LineWebhookPayload.class);

        assertThat(payload.events().getFirst().message().quotedMessageId()).isEqualTo("m1");
    }
}
