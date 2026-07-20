package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderDelivery;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import com.aproject.aidriven.mymobilesecretary.shared.time.TimeDisplayPreferenceService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationOutboxService {

    private final NotificationOutboxRepository repository;
    private final ReminderDeliveryRepository deliveryRepository;
    private final NotificationOutboxProperties properties;
    private final Clock clock;
    private final TimeDisplayPreferenceService timeDisplayPreferenceService;

    public NotificationOutboxService(NotificationOutboxRepository repository,
                                     ReminderDeliveryRepository deliveryRepository,
                                     NotificationOutboxProperties properties,
                                     Clock clock,
                                     TimeDisplayPreferenceService timeDisplayPreferenceService) {
        this.repository = repository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        this.clock = clock;
        this.timeDisplayPreferenceService = timeDisplayPreferenceService;
    }

    @Transactional
    public List<ClaimedNotification> claimDue(UUID targetUserId) {
        Instant now = Instant.now(clock);
        List<ClaimedNotification> claimed = new ArrayList<>();
        for (NotificationOutbox entry : repository.findClaimable(
                targetUserId, NotificationOutboxStatus.PENDING, now,
                PageRequest.of(0, properties.maxBatch()))) {
            UUID token = UUID.randomUUID();
            entry.claim(token, now.plus(properties.lease()));
            claimed.add(new ClaimedNotification(entry.getId(), token, entry.getChannel(),
                    entry.isRetrySafe(), entry.envelope(
                            timeDisplayPreferenceService::applyToHumanText)));
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public int recoverExpiredClaims(UUID targetUserId) {
        Instant now = Instant.now(clock);
        int recovered = 0;
        for (NotificationOutbox entry : repository.findExpiredClaims(
                targetUserId, NotificationOutboxStatus.SENDING, now,
                PageRequest.of(0, properties.maxBatch()))) {
            if (entry.isRetrySafe() && entry.getAttemptCount() < properties.maxAttempts()) {
                entry.retry(now.plus(properties.retryDelay()), "LEASE_EXPIRED");
            } else {
                entry.deadLetter(now, "DELIVERY_RESULT_UNKNOWN");
                recordFailure(entry, "DELIVERY_RESULT_UNKNOWN", now);
            }
            recovered++;
        }
        return recovered;
    }

    @Transactional
    public boolean markSent(long id, UUID claimToken) {
        NotificationOutbox entry = repository.findById(id).orElse(null);
        if (entry == null || !entry.claimMatches(claimToken)) {
            return false;
        }
        Instant now = Instant.now(clock);
        entry.markSent(now);
        if (entry.getReminderId() != null) {
            deliveryRepository.save(ReminderDelivery.success(
                    entry.getReminderId(), entry.getChannel(), now));
        }
        return true;
    }

    @Transactional
    public boolean markFailed(long id, UUID claimToken, String errorCode) {
        NotificationOutbox entry = repository.findById(id).orElse(null);
        if (entry == null || !entry.claimMatches(claimToken)) {
            return false;
        }
        Instant now = Instant.now(clock);
        recordFailure(entry, errorCode, now);
        if (entry.isRetrySafe() && entry.getAttemptCount() < properties.maxAttempts()) {
            entry.retry(now.plus(properties.retryDelay()), errorCode);
        } else {
            entry.deadLetter(now, errorCode);
        }
        return true;
    }

    private void recordFailure(NotificationOutbox entry, String code, Instant now) {
        if (entry.getReminderId() != null) {
            deliveryRepository.save(ReminderDelivery.failure(
                    entry.getReminderId(), entry.getChannel(), code, now));
        }
    }

    public record ClaimedNotification(
            long id,
            UUID claimToken,
            String channel,
            boolean retrySafe,
            ReminderNotification envelope
    ) {
    }
}
