package com.aproject.internal.aidispatcher.trigger.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MainApplicationFeedSourceTest {

    @Test
    void mapsVersionedFeedAndSendsDedicatedBearerCredential() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://main-app.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MainApplicationFeedSource source = new MainApplicationFeedSource(
                builder.build(),
                new ObjectMapper().findAndRegisterModules(),
                "secret-token");
        server.expect(requestTo(
                        "http://main-app.test/internal/integration/v1/development-events"
                                + "?limit=100&after=opaque-10"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        {
                          "events": [{
                            "eventId": "line-message:11",
                            "type": "line.conversation.recorded",
                            "occurredAt": "2026-07-17T00:00:00Z",
                            "subjectRef": "line-message:11",
                            "schemaVersion": 1,
                            "metadata": {"messageType": "TEXT", "text": "build feature"}
                          }],
                          "nextCursor": "opaque-11",
                          "hasMore": false
                        }
                        """, MediaType.APPLICATION_JSON));

        TriggerSourcePage page = source.fetchAfter("opaque-10", 100);

        assertThat(page.nextCursor()).isEqualTo("opaque-11");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.events()).singleElement().satisfies(event -> {
            assertThat(event.sourceEventId()).isEqualTo("line-message:11");
            assertThat(event.triggerType()).isEqualTo("line.conversation.recorded");
            assertThat(event.metadataJson()).contains("build feature");
        });
        server.verify();
    }
}
