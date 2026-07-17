package com.aproject.aidriven.mymobilesecretary.account.security.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.SecretTextCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final UUID WORKSPACE = UUID.fromString("60000000-0000-0000-0000-000000000101");
    private static final UUID ACTOR = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SecretTextCipher cipher;

    @Test
    void requestHashIsStableAndSeparatesDifferentBodies() {
        assertThat(IdempotencyService.sha256("同一個請求"))
                .isEqualTo(IdempotencyService.sha256("同一個請求"))
                .isNotEqualTo(IdempotencyService.sha256("另一個請求"))
                .hasSize(64);
    }

    @Test
    void executionBoundaryMustTransitionExactlyOneReservedRow() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service().markExecutionStarted(WORKSPACE, ACTOR, "line", "event-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("status = 'UNKNOWN'", "status = 'RESERVED'");
    }

    @Test
    void staleBoundaryNeverAllowsMutationToProceed() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> service().markExecutionStarted(
                WORKSPACE, ACTOR, "LINE", "event-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start execution");
    }

    @Test
    void completionOnlyConsumesUnknownExecutionState() {
        when(cipher.encrypt("done")).thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service().complete(WORKSPACE, ACTOR, "LINE", "event-1", "CREATE_TASK", "done");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("status = 'COMPLETED'", "status = 'UNKNOWN'");
    }

    private IdempotencyService service() {
        return new IdempotencyService(jdbcTemplate, cipher,
                new IdempotencyProperties(Duration.ofHours(24)),
                Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC));
    }
}
