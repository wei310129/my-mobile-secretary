package com.aproject.aidriven.mymobilesecretary.account.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SecurityAuditEventTest {

    @Test
    void sanitizesMetadataWithoutAcceptingRawMultilineContent() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        SecurityAuditEvent event = SecurityAuditEvent.from(new SecurityAuditDraft(
                null, null, "WORKSPACE_ACCESS_DENIED\nignored", "TASK", "42",
                SecurityAuditEvent.Outcome.DENIED, "NOT_MEMBER", "REST", null),
                now, now.plusSeconds(60));

        assertThat(event.getEventType()).isEqualTo("WORKSPACE_ACCESS_DENIED ignored");
        assertThat(event.getOutcome()).isEqualTo(SecurityAuditEvent.Outcome.DENIED);
    }
}
