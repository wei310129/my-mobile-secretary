package com.aproject.internal.aidispatcher.session.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "ai-dispatcher.enabled=false",
        "ai-dispatcher.session-binding-api.enabled=true",
        "ai-dispatcher.session-binding-api.admin-token=0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@Testcontainers
class SessionBindingControllerIntegrationTest {

    private static final String PATH = "/internal/v1/session-bindings/development-main";
    private static final String AUTHORIZATION =
            "Bearer 0123456789abcdef0123456789abcdef";
    private static final String SESSION_ID =
            "0199a213-81c0-7800-8aa1-bbab2a035a53";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'IDLE', active_run_id = NULL, fencing_token = 0,
                    observed_first_pending_at = NULL, observed_last_pending_at = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    consecutive_failure_count = 0, last_error_code = NULL,
                    paused_reason = NULL, version = 0, updated_at = CURRENT_TIMESTAMP
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """);
        jdbcTemplate.update("DELETE FROM dispatcher_run_event");
        jdbcTemplate.update("DELETE FROM dispatcher_event");
        jdbcTemplate.update("DELETE FROM dispatcher_run");
        jdbcTemplate.update("DELETE FROM agent_session_binding_audit");
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'UNBOUND', external_session_id = NULL,
                    bound_at = NULL, last_verified_at = NULL,
                    version = 0, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
    }

    @Test
    void rejectsMissingAndIncorrectBearerTokens() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        mockMvc.perform(get(PATH).header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheCurrentBindingWithoutAllowingCaching() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.sessionKey").value("development-main"))
                .andExpect(jsonPath("$.displayName").value("開發主要對話"))
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.externalSessionId").doesNotExist());
    }

    @Test
    void bindsWithActorAuditAndOptimisticVersion() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .header("X-Dispatcher-Actor", "operator-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalSessionId": "%s",
                                  "expectedVersion": 0,
                                  "reason": "Initial technical thread binding"
                                }
                                """.formatted(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.externalSessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT actor_id FROM agent_session_binding_audit
                """, String.class)).isEqualTo("operator-a");
    }

    @Test
    void rejectsAStaleManagementWrite() throws Exception {
        bindVersionZero();

        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .header("X-Dispatcher-Actor", "operator-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalSessionId": "0199a213-81c0-7800-8aa1-bbab2a035a54",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_BINDING_CONFLICT"));
    }

    @Test
    void unbindsAndPausesDispatching() throws Exception {
        bindVersionZero();

        mockMvc.perform(delete(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .header("X-Dispatcher-Actor", "operator-a")
                        .param("expectedVersion", "1")
                        .param("reason", "Thread is no longer available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNBOUND"))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, String.class)).isEqualTo("PAUSED");
    }

    @Test
    void requiresAnActorAndExpectedVersionForWrites() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId": "%s"}
                                """.formatted(SESSION_ID)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .header("X-Dispatcher-Actor", "operator-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId": "%s", "expectedVersion": 0}
                                """.formatted(SESSION_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId": "%s", "expectedVersion": 1}
                                """.formatted(SESSION_ID)))
                .andExpect(status().isBadRequest());
    }

    private void bindVersionZero() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", AUTHORIZATION)
                        .header("X-Dispatcher-Actor", "operator-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId": "%s", "expectedVersion": 0}
                                """.formatted(SESSION_ID)))
                .andExpect(status().isOk());
    }
}
