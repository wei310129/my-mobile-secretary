package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxWorkerTest {

    private static final UUID TARGET =
            UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Mock private NotificationOutboxService service;
    @Mock private WorkspaceBackgroundRunner workspaceRunner;
    @Mock private NotificationSender sender;

    @Test
    void successfulProviderSendIsAcknowledgedWithTheClaimToken() {
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(sender.supportsStableDeliveryId()).thenReturn(true);
        NotificationOutboxService.ClaimedNotification claim = claim(true);
        when(service.claimDue(TARGET)).thenReturn(List.of(claim));
        when(service.markSent(claim.id(), claim.claimToken())).thenReturn(true);

        worker().process(TARGET);

        verify(sender).send(claim.envelope());
        verify(service).markSent(claim.id(), claim.claimToken());
    }

    @Test
    void changedIdempotencyContractFailsClosedBeforeSending() {
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(sender.supportsStableDeliveryId()).thenReturn(false);
        NotificationOutboxService.ClaimedNotification claim = claim(true);
        when(service.claimDue(TARGET)).thenReturn(List.of(claim));

        worker().process(TARGET);

        verify(sender, never()).send(claim.envelope());
        verify(service).markFailed(claim.id(), claim.claimToken(),
                "IDEMPOTENCY_CONTRACT_CHANGED");
    }

    private NotificationOutboxWorker worker() {
        return new NotificationOutboxWorker(service, workspaceRunner, List.of(sender));
    }

    private static NotificationOutboxService.ClaimedNotification claim(boolean retrySafe) {
        UUID token = UUID.randomUUID();
        ReminderNotification envelope = new ReminderNotification(
                UUID.fromString("50000000-0000-0000-0000-000000000101"),
                TARGET,
                UUID.randomUUID(), "server-log", 9L, 7L, "提醒", "該出門了");
        return new NotificationOutboxService.ClaimedNotification(
                11L, token, "LOG", retrySafe, envelope);
    }
}
