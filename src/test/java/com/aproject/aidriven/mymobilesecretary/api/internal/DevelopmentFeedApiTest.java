package com.aproject.aidriven.mymobilesecretary.api.internal;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.integration.developmentfeed.DevelopmentFeedCursorCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.integration.development-feed.enabled=true",
        "app.integration.development-feed.bearer-token=test-development-feed-token-1234567890",
        "app.integration.development-feed.workspace-id=00000000-0000-0000-0000-000000000101",
        "app.integration.development-feed.actor-id=00000000-0000-0000-0000-000000000001",
        "app.integration.development-feed.max-page-size=100"
})
class DevelopmentFeedApiTest extends IntegrationTestBase {

    private static final String PATH = "/internal/integration/v1/development-events";
    private static final String TOKEN = "test-development-feed-token-1234567890";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DevelopmentFeedCursorCodec cursorCodec;

    @Test
    void dedicatedTokenReadsOnlyInboundMessagesForItsConfiguredActor() throws Exception {
        long baseline = maximumMessageId();
        long inboundId = insertMessage(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "IN", "TEXT", "dispatcher integration request " + UUID.randomUUID());
        insertMessage(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OUT", "TEXT", "bot reply must not trigger");

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", cursorCodec.encode(baseline))
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId")
                        .value("line-message:" + inboundId))
                .andExpect(jsonPath("$.events[0].type")
                        .value("line.conversation.recorded"))
                .andExpect(jsonPath("$.events[0].metadata.messageType").value("TEXT"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void authenticationIsFailClosedAndNonGetMethodsAreDenied() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void opaqueCursorPaginatesWithoutDuplicates() throws Exception {
        long baseline = maximumMessageId();
        long firstId = insertMessage(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "IN", "TEXT", "first " + UUID.randomUUID());
        long secondId = insertMessage(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "IN", "TEXT", "second " + UUID.randomUUID());

        String firstResponse = mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", cursorCodec.encode(baseline))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId").value("line-message:" + firstId))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String nextCursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstResponse).path("nextCursor").asText();

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId").value("line-message:" + secondId));
    }

    @Test
    void invalidCursorAndPageSizeAreBadRequests() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", "invalid!"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    private long maximumMessageId() {
        Long maximum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM line_message_log", Long.class);
        return maximum == null ? 0 : maximum;
    }

    private long insertMessage(UUID workspaceId, UUID actorId,
                               String direction, String messageType, String content) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO line_message_log (
                    direction, message_type, content, created_at, pinned, expires_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, FALSE, ?, ?, ?)
                RETURNING id
                """, Long.class, direction, messageType, content,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86400)),
                workspaceId, actorId);
    }
}
