package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderPreferenceService;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CalendarReminderOccurrenceWorker {

    private static final int MAX_BATCH = 100;

    private final CalendarReminderOccurrenceRepository occurrences;
    private final CalendarReminderRuleRepository rules;
    private final CalendarTimeNodeRepository nodes;
    private final CalendarReminderApplicationService reminderService;
    private final ReminderPreferenceService preferences;
    private final NotificationPublisher notifications;
    private final WorkspaceBackgroundRunner workspaceRunner;
    private final Clock clock;

    public CalendarReminderOccurrenceWorker(
            CalendarReminderOccurrenceRepository occurrences,
            CalendarReminderRuleRepository rules,
            CalendarTimeNodeRepository nodes,
            CalendarReminderApplicationService reminderService,
            ReminderPreferenceService preferences,
            NotificationPublisher notifications,
            WorkspaceBackgroundRunner workspaceRunner,
            Clock clock) {
        this.occurrences = occurrences;
        this.rules = rules;
        this.nodes = nodes;
        this.reminderService = reminderService;
        this.preferences = preferences;
        this.notifications = notifications;
        this.workspaceRunner = workspaceRunner;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.calendar.reminder.poll-interval:30s}")
    public void poll() {
        workspaceRunner.forEachActor(
                "calendar-reminder-occurrence",
                context -> process(context.actorId()));
    }

    @Transactional
    public void process(UUID targetUserId) {
        WorkspaceContext context = tenantContext();
        if (!context.actorId().equals(targetUserId)) {
            throw new SecurityException("Calendar reminder target must match the actor");
        }
        Instant now = Instant.now(clock);
        for (CalendarReminderOccurrenceEntity occurrence :
                occurrences
                        .findByStatusAndScheduledAtLessThanEqualAndWorkspaceIdAndCreatedByUserIdOrderByScheduledAt(
                                CalendarReminderOccurrenceEntity.Status.PENDING,
                                now,
                                context.workspaceId(),
                                context.actorId(),
                                PageRequest.of(0, MAX_BATCH))) {
            CalendarReminderRuleEntity rule = rules
                    .findByIdAndWorkspaceIdAndCreatedByUserId(
                            occurrence.getRuleId(),
                            context.workspaceId(),
                            context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar reminder rule", "due rule"));
            if (!rule.isActive()) {
                occurrence.cancel(now);
                continue;
            }
            CalendarTimeNodeEntity node = nodes
                    .findByIdAndWorkspaceIdAndCreatedByUserId(
                            rule.getNodeId(),
                            context.workspaceId(),
                            context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar node", "due reminder node"));
            if (node.isCanceled()
                    || occurrence.getNodeRevision() != node.getRevision()) {
                occurrence.cancel(now);
                continue;
            }
            var deferUntil = preferences.deferUntil(
                    node.getCriticality() == Criticality.CRITICAL, now);
            if (deferUntil.isPresent()) {
                occurrence.deferUntil(deferUntil.get(), now);
                continue;
            }
            Set<com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel>
                    allowed = rule.getPreferredChannel() == null
                            ? Set.of()
                            : Set.of(rule.getPreferredChannel());
            int inserted = notifications.enqueue(new NotificationRequest(
                    targetUserId,
                    "calendar-reminder:" + occurrence.getId(),
                    null,
                    null,
                    node.getLabel(),
                    "Calendar reminder: " + node.getLabel(),
                    allowed));
            if (inserted > 0) {
                occurrence.enqueue(now);
                reminderService.materializeNextAck(
                        rule.getId(), occurrence.getSequenceNumber());
            }
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar reminder worker requires a tenant workspace");
        }
        return context;
    }
}
