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
        "app.integration.development-feed.max-page-size=100",
        "app.integration.development-feed.max-payload-bytes=4096"
})
class DevelopmentFeedApiTest extends IntegrationTestBase {

    private static final String PATH = "/internal/integration/v2/development-issues";
    private static final String TOKEN = "test-development-feed-token-1234567890";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DevelopmentFeedCursorCodec cursorCodec;

    @Test
    void dedicatedTokenReadsOnlyOpenIntentIssuesForItsConfiguredActor() throws Exception {
        long baseline = maximumIssueId();
        long openIssueId = insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OPEN", "FEEDBACK", "dispatcher integration request " + UUID.randomUUID());
        insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "RESOLVED", "FEEDBACK", "resolved issue must not trigger");
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", cursorCodec.encode(baseline))
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId")
                        .value("intent-issue:" + openIssueId))
                .andExpect(jsonPath("$.events[0].type")
                        .value("intent.issue.opened"))
                .andExpect(jsonPath("$.events[0].schemaVersion").value(2))
                .andExpect(jsonPath("$.events[0].metadata.category").value("FEEDBACK"))
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
        long baseline = maximumIssueId();
        long firstId = insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OPEN", "CLARIFICATION", "first " + UUID.randomUUID());
        long secondId = insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OPEN", "FALLBACK", "second " + UUID.randomUUID());

        String firstResponse = mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", cursorCodec.encode(baseline))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId").value("intent-issue:" + firstId))
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
                .andExpect(jsonPath("$.events[0].eventId").value("intent-issue:" + secondId));
    }

    @Test
    void payloadBudgetSplitsLargeIssuesWithoutLosingCursorProgress() throws Exception {
        long baseline = maximumIssueId();
        String largeText = "需".repeat(500);
        long firstId = insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OPEN", "FEEDBACK", largeText, largeText);
        insertIssue(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "OPEN", "FEEDBACK", largeText, largeText);

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("after", cursorCodec.encode(baseline))
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventId").value("intent-issue:" + firstId))
                .andExpect(jsonPath("$.hasMore").value(true));
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

    private long maximumIssueId() {
        Long maximum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM intent_issue", Long.class);
        return maximum == null ? 0 : maximum;
    }

    private long insertIssue(UUID workspaceId, UUID actorId,
                             String status, String category, String utterance) {
        return insertIssue(workspaceId, actorId, status, category, utterance, "test bot reply");
    }

    private long insertIssue(UUID workspaceId, UUID actorId,
                             String status, String category, String utterance, String botReply) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO intent_issue (
                    utterance, bot_reply, category, status, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, utterance, botReply, category, status,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()),
                workspaceId, actorId);
    }
}
