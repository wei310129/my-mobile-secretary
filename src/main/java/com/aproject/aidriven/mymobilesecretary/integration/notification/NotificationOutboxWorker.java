package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);

    private final NotificationOutboxService service;
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final Map<NotificationChannel, NotificationSender> senders;

    public NotificationOutboxWorker(NotificationOutboxService service,
                                    WorkspaceBackgroundRunner workspaceRunner,
                                    List<NotificationSender> senderList) {
        this.service = service;
        this.workspaceRunner = workspaceRunner;
        EnumMap<NotificationChannel, NotificationSender> indexed =
                new EnumMap<>(NotificationChannel.class);
        for (NotificationSender sender : senderList) {
            NotificationSender duplicate = indexed.put(sender.channel(), sender);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate notification sender for " + sender.channel());
            }
        }
        this.senders = Map.copyOf(indexed);
    }

    @Scheduled(fixedDelayString = "${app.notification.outbox.poll-interval:5s}")
    public void poll() {
        workspaceRunner.forEachWorkspace("notification-outbox", ignored -> process());
    }

    public void process() {
        int recovered = service.recoverExpiredClaims();
        if (recovered > 0) {
            log.warn("Recovered notification delivery leases [count={}]", recovered);
        }
        for (NotificationOutboxService.ClaimedNotification claim : service.claimDue()) {
            NotificationSender sender;
            try {
                sender = senders.get(NotificationChannel.valueOf(claim.channel()));
            } catch (IllegalArgumentException invalidChannel) {
                sender = null;
            }
            if (sender == null) {
                service.markFailed(claim.id(), claim.claimToken(), "CHANNEL_UNAVAILABLE");
                continue;
            }
            if (claim.retrySafe() && !sender.supportsStableDeliveryId()) {
                service.markFailed(claim.id(), claim.claimToken(), "IDEMPOTENCY_CONTRACT_CHANGED");
                continue;
            }
            try {
                sender.send(claim.envelope());
                if (!service.markSent(claim.id(), claim.claimToken())) {
                    log.warn("Notification sent but lease was stale [delivery={}]",
                            SensitiveValueFingerprint.of(
                                    claim.envelope().deliveryId().toString()));
                }
            } catch (RuntimeException failure) {
                service.markFailed(claim.id(), claim.claimToken(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
