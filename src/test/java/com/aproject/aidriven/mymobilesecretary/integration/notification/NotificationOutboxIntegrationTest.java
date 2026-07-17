package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class NotificationOutboxIntegrationTest extends IntegrationTestBase {

    @Autowired private NotificationPublisher publisher;
    @Autowired private NotificationOutboxService outboxService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @Transactional
    void persistsClaimsAndErasesPayloadAfterFencedAcknowledgement() {
        String deliveryKey = "integration:" + UUID.randomUUID();
        UUID deliveryId = NotificationPublisher.stableDeliveryId(
                LegacyAccountIds.WORKSPACE_ID, deliveryKey, "LOG");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID,
                        WorkspaceChannel.TEST))) {
            assertThat(publisher.enqueue(new NotificationRequest(
                    LegacyAccountIds.USER_ID, deliveryKey,  null, null,
                    "私密提醒", "不應永久留在 outbox"))).isEqualTo(1);

            NotificationOutboxService.ClaimedNotification claim = outboxService.claimDue().stream()
                    .filter(candidate -> deliveryId.equals(candidate.envelope().deliveryId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(claim.envelope().workspaceId()).isEqualTo(LegacyAccountIds.WORKSPACE_ID);
            assertThat(claim.envelope().targetUserId()).isEqualTo(LegacyAccountIds.USER_ID);
            assertThat(outboxService.markSent(claim.id(), claim.claimToken())).isTrue();
            entityManager.flush();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE delivery_id = ?",
                String.class, deliveryId)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title IS NULL AND message IS NULL FROM notification_outbox WHERE delivery_id = ?",
                Boolean.class, deliveryId)).isTrue();
    }
}
