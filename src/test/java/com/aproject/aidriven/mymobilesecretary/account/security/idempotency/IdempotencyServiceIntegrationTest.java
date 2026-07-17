package com.aproject.aidriven.mymobilesecretary.account.security.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.intent.application.AesGcmSecretTextCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reservesReplaysRejectsConflictsAndAllowsFailedRetry() {
        IdempotencyService service = new IdempotencyService(
                jdbcTemplate,
                new AesGcmSecretTextCipher(
                        "0123456789abcdef".getBytes(StandardCharsets.UTF_8), "test-key"),
                new IdempotencyProperties(Duration.ofHours(24)),
                Clock.systemUTC());
        String key = "integration-" + UUID.randomUUID();

        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "request-one").state()).isEqualTo(IdempotencyService.State.NEW);
        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "request-one").state()).isEqualTo(IdempotencyService.State.IN_PROGRESS);
        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "different").state()).isEqualTo(IdempotencyService.State.CONFLICT);

        service.markExecutionStarted(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key);
        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "request-one").state())
                .isEqualTo(IdempotencyService.State.RESULT_UNKNOWN);
        service.complete(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "CREATE_TASK", "已建立任務");
        IdempotencyService.BeginResult replay = service.begin(
                LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", key, "request-one");
        assertThat(replay.state()).isEqualTo(IdempotencyService.State.REPLAY_AVAILABLE);
        assertThat(replay.responseAction()).isEqualTo("CREATE_TASK");
        assertThat(replay.responseBody()).isEqualTo("已建立任務");

        String failedKey = key + "-failed";
        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", failedKey, "request-two").state()).isEqualTo(IdempotencyService.State.NEW);
        service.failBeforeExecution(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", failedKey, "TEMPORARY_FAILURE");
        assertThat(service.begin(LegacyAccountIds.WORKSPACE_ID, LegacyAccountIds.USER_ID,
                "LINE", failedKey, "request-two").state()).isEqualTo(IdempotencyService.State.NEW);

        jdbcTemplate.update("DELETE FROM idempotency_record WHERE idempotency_key IN (?, ?)",
                key, failedKey);
    }
}
