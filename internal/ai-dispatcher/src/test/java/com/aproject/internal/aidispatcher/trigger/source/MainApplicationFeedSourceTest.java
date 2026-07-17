package com.aproject.internal.aidispatcher.trigger.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                        "http://main-app.test/internal/integration/v2/development-issues"
                                + "?limit=100&after=opaque-10"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        {
                          "events": [{
                            "eventId": "intent-issue:11",
                            "type": "intent.issue.opened",
                            "occurredAt": "2026-07-17T00:00:00Z",
                            "subjectRef": "intent-issue:11",
                            "schemaVersion": 2,
                            "metadata": {"category": "unsupported_intent", "utterance": "build feature"}
                          }],
                          "nextCursor": "opaque-11",
                          "hasMore": false
                        }
                        """, MediaType.APPLICATION_JSON));

        TriggerSourcePage page = source.fetchAfter("opaque-10", 100);

        assertThat(page.nextCursor()).isEqualTo("opaque-11");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.events()).singleElement().satisfies(event -> {
            assertThat(event.sourceEventId()).isEqualTo("intent-issue:11");
            assertThat(event.triggerType()).isEqualTo("intent.issue.opened");
            assertThat(event.metadataJson()).contains("build feature");
        });
        server.verify();
    }

    @Test
    void rejectsAnUnexpectedEventContractWithoutAdvancingTheCursor() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://main-app.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MainApplicationFeedSource source = new MainApplicationFeedSource(
                builder.build(), new ObjectMapper().findAndRegisterModules(), "secret-token");
        server.expect(requestTo(
                        "http://main-app.test/internal/integration/v2/development-issues?limit=20"))
                .andRespond(withSuccess("""
                        {
                          "events": [{
                            "eventId": "unexpected:1",
                            "type": "line.conversation.recorded",
                            "occurredAt": "2026-07-17T00:00:00Z",
                            "subjectRef": "unexpected:1",
                            "schemaVersion": 1,
                            "metadata": {}
                          }],
                          "nextCursor": "opaque-1",
                          "hasMore": false
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchAfter(null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported event contract");
        server.verify();
    }
}
