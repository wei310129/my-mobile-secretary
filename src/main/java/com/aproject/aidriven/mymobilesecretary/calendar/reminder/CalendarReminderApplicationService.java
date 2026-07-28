package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeCanceledEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeRevisedEvent;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarReminderApplicationService {

    private static final int ACTIVE_RULE_QUOTA = 8;

    private final CalendarApplicationService calendars;
    private final CalendarTimeNodeRepository nodes;
    private final CalendarReminderRuleRepository rules;
    private final CalendarReminderOccurrenceRepository occurrences;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CalendarReminderApplicationService(
            CalendarApplicationService calendars,
            CalendarTimeNodeRepository nodes,
            CalendarReminderRuleRepository rules,
            CalendarReminderOccurrenceRepository occurrences,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            Clock clock) {
        this.calendars = calendars;
        this.nodes = nodes;
        this.rules = rules;
        this.occurrences = occurrences;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public CalendarReminderRuleView createRelative(
            String planKey,
            String nodeKey,
            Duration offset,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        if (offset == null || offset.isPositive()) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK",
                    "A reminder after a calendar node must be created as a Task");
        }
        CalendarTimeNodeEntity node = lockedNode(planKey, nodeKey);
        return createRelative(
                node,
                offset,
                ownerKind,
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel,
                nodeKey);
    }

    public CalendarReminderRuleView createPersonalRelativeForNode(
            UUID planId,
            UUID nodeId,
            long expectedNodeRevision,
            Duration offset,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        if (offset == null || offset.isPositive()) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK",
                    "A reminder after a calendar node must be created as a Task");
        }
        WorkspaceContext context = tenantContext();
        CalendarTimeNodeEntity owned =
                ownedNode(planId, nodeId, expectedNodeRevision, context);
        if (owned != null) {
            return createRelative(
                    owned,
                    offset,
                    CalendarReminderOwnerKind.PERSONAL,
                    deliveryMode,
                    ackInterval,
                    maxAlerts,
                    preferredChannel,
                    owned.getLabel());
        }
        PersonalProjectionTarget shared = personalProjectionTarget(
                planId, nodeId, expectedNodeRevision, context);
        return createPersonalRelative(
                shared,
                offset,
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel);
    }

    private CalendarReminderRuleView createRelative(
            CalendarTimeNodeEntity node,
            Duration offset,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel,
            String eventLabel) {
        CalendarReminderDeliveryMode resolvedMode =
                requireDeliveryMode(node, deliveryMode);
        requireNewRelativeSemantics(node, ownerKind, offset);
        requireQuota(node, ownerKind);
        Instant now = Instant.now(clock);
        CalendarReminderRuleEntity rule = rules.saveAndFlush(
                CalendarReminderRuleEntity.relative(
                        UUID.randomUUID(),
                        node.getPlanId(),
                        node.getId(),
                        node.getCreatedByUserId(),
                        null,
                        ownerKind,
                        offset,
                        resolvedMode,
                        ackInterval,
                        maxAlerts,
                        preferredChannel,
                        now));
        Instant scheduledAt = rule.scheduleFrom(requireResolvedTime(node));
        materializeFirstIfPersonal(rule, node.getRevision(), scheduledAt, now);
        publishCreated(rule, eventLabel, now);
        return new CalendarReminderRuleView(
                rule.getId(), ownerKind, resolvedMode, scheduledAt);
    }

    public CalendarReminderRuleView createAbsolute(
            String planKey,
            String nodeKey,
            Instant fireAt,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        CalendarTimeNodeEntity node = lockedNode(planKey, nodeKey);
        if (fireAt == null || fireAt.isAfter(requireResolvedTime(node))) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK",
                    "A reminder after a calendar node must be created as a Task");
        }
        return createAbsolute(
                node,
                fireAt,
                ownerKind,
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel,
                nodeKey);
    }

    public CalendarReminderRuleView createPersonalAbsoluteForNode(
            UUID planId,
            UUID nodeId,
            long expectedNodeRevision,
            Instant fireAt,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        WorkspaceContext context = tenantContext();
        CalendarTimeNodeEntity owned =
                ownedNode(planId, nodeId, expectedNodeRevision, context);
        if (owned != null) {
            if (fireAt == null || fireAt.isAfter(requireResolvedTime(owned))) {
                throw new BusinessException(
                        "CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK",
                        "A reminder after a calendar node must be created as a Task");
            }
            return createAbsolute(
                    owned,
                    fireAt,
                    CalendarReminderOwnerKind.PERSONAL,
                    deliveryMode,
                    ackInterval,
                    maxAlerts,
                    preferredChannel,
                    owned.getLabel());
        }
        PersonalProjectionTarget shared = personalProjectionTarget(
                planId, nodeId, expectedNodeRevision, context);
        if (fireAt == null || fireAt.isAfter(shared.resolvedTime())) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_AFTER_NODE_REQUIRES_TASK",
                    "A reminder after a calendar node must be created as a Task");
        }
        return createPersonalAbsolute(
                shared,
                fireAt,
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel);
    }

    private CalendarReminderRuleView createAbsolute(
            CalendarTimeNodeEntity node,
            Instant fireAt,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel,
            String eventLabel) {
        CalendarReminderDeliveryMode resolvedMode =
                requireDeliveryMode(node, deliveryMode);
        requireNewAbsoluteSemantics(node, ownerKind, fireAt);
        requireQuota(node, ownerKind);
        Instant now = Instant.now(clock);
        CalendarReminderRuleEntity rule = rules.saveAndFlush(
                CalendarReminderRuleEntity.absolute(
                        UUID.randomUUID(),
                        node.getPlanId(),
                        node.getId(),
                        node.getCreatedByUserId(),
                        null,
                        ownerKind,
                        fireAt,
                        resolvedMode,
                        ackInterval,
                        maxAlerts,
                        preferredChannel,
                        now));
        Instant scheduledAt = rule.scheduleFrom(requireResolvedTime(node));
        materializeFirstIfPersonal(rule, node.getRevision(), scheduledAt, now);
        publishCreated(rule, eventLabel, now);
        return new CalendarReminderRuleView(
                rule.getId(), ownerKind, resolvedMode, scheduledAt);
    }

    public void acknowledge(UUID ruleId) {
        WorkspaceContext context = tenantContext();
        CalendarReminderRuleEntity rule = authorizedRule(ruleId, context);
        Instant now = Instant.now(clock);
        rule.acknowledge(now);
        cancelPending(rule, context, now);
    }

    public void cancel(UUID ruleId) {
        WorkspaceContext context = tenantContext();
        CalendarReminderRuleEntity rule = authorizedRule(ruleId, context);
        Instant now = Instant.now(clock);
        rule.cancel(now);
        cancelPending(rule, context, now);
        events.publishEvent(new CalendarReminderLifecycleEvent(
                rule.getId(),
                CalendarReminderLifecycleEvent.Action.CANCELED,
                "calendar node",
                now));
    }

    public void materializeNextAck(UUID ruleId, int completedSequence) {
        WorkspaceContext context = tenantContext();
        CalendarReminderRuleEntity rule = authorizedRule(ruleId, context);
        if (!rule.isActive() || !rule.needsAckAfter(completedSequence)) {
            return;
        }
        CalendarReminderOccurrenceEntity previous = occurrences
                .findFirstByRuleIdAndWorkspaceIdAndCreatedByUserIdOrderBySequenceNumberDesc(
                        ruleId, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar reminder occurrence", "latest occurrence"));
        if (previous.getSequenceNumber() > completedSequence) {
            return;
        }
        Instant now = Instant.now(clock);
        occurrences.saveAndFlush(CalendarReminderOccurrenceEntity.pending(
                UUID.randomUUID(),
                rule,
                previous.getNodeRevision(),
                completedSequence + 1,
                rule.nextAckAt(previous.getScheduledAt()),
                now));
    }

    @EventListener
    public void onNodeRevised(CalendarNodeRevisedEvent event) {
        WorkspaceContext context = tenantContext();
        Instant now = event.occurredAt();
        for (CalendarReminderRuleEntity rule :
                rules.findAllByNodeIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                        event.nodeId(),
                        CalendarReminderRuleEntity.Status.ACTIVE,
                        context.workspaceId(),
                        context.actorId())) {
            cancelPending(rule, context, now);
            if (rule.isRelative()) {
                occurrences.save(CalendarReminderOccurrenceEntity.pending(
                        UUID.randomUUID(),
                        rule,
                        event.currentRevision(),
                        0,
                        rule.scheduleFrom(event.currentEffectiveTime()),
                        now));
            } else {
                rule.requireReview(now);
            }
        }
    }

    @EventListener
    public void onNodeCanceled(CalendarNodeCanceledEvent event) {
        WorkspaceContext context = tenantContext();
        Instant now = event.occurredAt();
        for (CalendarReminderRuleEntity rule :
                rules.findAllByNodeIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                        event.nodeId(),
                        CalendarReminderRuleEntity.Status.ACTIVE,
                        context.workspaceId(),
                        context.actorId())) {
            rule.cancel(now);
            cancelPending(rule, context, now);
        }
    }

    private CalendarTimeNodeEntity lockedNode(String planKey, String nodeKey) {
        return calendars.lockNodeForReminder(planKey, nodeKey);
    }

    private CalendarTimeNodeEntity ownedNode(
            UUID planId,
            UUID nodeId,
            long expectedNodeRevision,
            WorkspaceContext context) {
        if (planId == null || nodeId == null || expectedNodeRevision <= 0) {
            throw new IllegalArgumentException(
                    "plan, node, and positive node revision are required");
        }
        return nodes
                .findWithLockByIdAndPlanIdAndWorkspaceIdAndCreatedByUserId(
                        nodeId,
                        planId,
                        context.workspaceId(),
                        context.actorId())
                .map(node -> {
                    requireExpectedActiveNode(node, expectedNodeRevision);
                    return node;
                })
                .orElse(null);
    }

    private PersonalProjectionTarget personalProjectionTarget(
            UUID planId,
            UUID nodeId,
            long expectedNodeRevision,
            WorkspaceContext context) {
        List<PersonalProjectionTarget> targets = jdbc.query(
                """
                SELECT snapshot.id AS snapshot_id,
                       projection.source_created_by_user_id,
                       projection.criticality,
                       projection.resolved_time,
                       projection.source_node_revision,
                       projection.label
                FROM calendar_personal_projection_snapshot snapshot
                JOIN calendar_personal_projection_node projection
                  ON projection.snapshot_id = snapshot.id
                 AND projection.workspace_id = snapshot.workspace_id
                 AND projection.created_by_user_id =
                        snapshot.created_by_user_id
                WHERE snapshot.plan_id = ?
                  AND projection.source_node_id = ?
                  AND snapshot.projection_status = 'ACTIVE'
                  AND snapshot.workspace_id = ?
                  AND snapshot.created_by_user_id = ?
                ORDER BY snapshot.projection_revision DESC
                LIMIT 1
                """,
                (row, ignored) -> {
                    Timestamp resolved =
                            row.getTimestamp("resolved_time");
                    return new PersonalProjectionTarget(
                            planId,
                            nodeId,
                            row.getObject("snapshot_id", UUID.class),
                            row.getObject(
                                    "source_created_by_user_id",
                                    UUID.class),
                            Criticality.valueOf(
                                    row.getString("criticality")),
                            resolved == null
                                    ? null
                                    : resolved.toInstant(),
                            row.getLong("source_node_revision"),
                            row.getString("label"));
                },
                planId,
                nodeId,
                context.workspaceId(),
                context.actorId());
        PersonalProjectionTarget target = targets.stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Calendar personal projection",
                        "adopted reminder target"));
        if (target.nodeRevision() != expectedNodeRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar node changed; reload it before creating a reminder");
        }
        if (target.resolvedTime() == null) {
            throw new BusinessException(
                    "CALENDAR_NODE_TIME_UNRESOLVED",
                    "The calendar node time cannot be resolved safely");
        }
        return target;
    }

    private CalendarReminderRuleView createPersonalRelative(
            PersonalProjectionTarget target,
            Duration offset,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        CalendarReminderDeliveryMode resolvedMode =
                requireDeliveryMode(target.criticality(), deliveryMode);
        requireNewRelativeSemantics(
                target.nodeId(),
                CalendarReminderOwnerKind.PERSONAL,
                offset);
        requireQuota(
                target.nodeId(), CalendarReminderOwnerKind.PERSONAL);
        Instant now = Instant.now(clock);
        CalendarReminderRuleEntity rule = rules.saveAndFlush(
                CalendarReminderRuleEntity.relative(
                        UUID.randomUUID(),
                        target.planId(),
                        target.nodeId(),
                        target.sourceOwnerId(),
                        target.snapshotId(),
                        CalendarReminderOwnerKind.PERSONAL,
                        offset,
                        resolvedMode,
                        ackInterval,
                        maxAlerts,
                        preferredChannel,
                        now));
        Instant scheduledAt =
                rule.scheduleFrom(target.resolvedTime());
        materializeFirstIfPersonal(
                rule, target.nodeRevision(), scheduledAt, now);
        publishCreated(rule, target.label(), now);
        return new CalendarReminderRuleView(
                rule.getId(),
                CalendarReminderOwnerKind.PERSONAL,
                resolvedMode,
                scheduledAt);
    }

    private CalendarReminderRuleView createPersonalAbsolute(
            PersonalProjectionTarget target,
            Instant fireAt,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel) {
        CalendarReminderDeliveryMode resolvedMode =
                requireDeliveryMode(target.criticality(), deliveryMode);
        requireNewAbsoluteSemantics(
                target.nodeId(),
                CalendarReminderOwnerKind.PERSONAL,
                fireAt);
        requireQuota(
                target.nodeId(), CalendarReminderOwnerKind.PERSONAL);
        Instant now = Instant.now(clock);
        CalendarReminderRuleEntity rule = rules.saveAndFlush(
                CalendarReminderRuleEntity.absolute(
                        UUID.randomUUID(),
                        target.planId(),
                        target.nodeId(),
                        target.sourceOwnerId(),
                        target.snapshotId(),
                        CalendarReminderOwnerKind.PERSONAL,
                        fireAt,
                        resolvedMode,
                        ackInterval,
                        maxAlerts,
                        preferredChannel,
                        now));
        materializeFirstIfPersonal(
                rule, target.nodeRevision(), fireAt, now);
        publishCreated(rule, target.label(), now);
        return new CalendarReminderRuleView(
                rule.getId(),
                CalendarReminderOwnerKind.PERSONAL,
                resolvedMode,
                fireAt);
    }

    private void requireQuota(
            CalendarTimeNodeEntity node, CalendarReminderOwnerKind ownerKind) {
        requireQuota(node.getId(), ownerKind);
    }

    private void requireQuota(
            UUID nodeId, CalendarReminderOwnerKind ownerKind) {
        WorkspaceContext context = tenantContext();
        long current = rules
                .countByNodeIdAndOwnerKindAndStatusAndWorkspaceIdAndCreatedByUserId(
                        nodeId,
                        ownerKind,
                        CalendarReminderRuleEntity.Status.ACTIVE,
                        context.workspaceId(),
                        context.actorId());
        if (current >= ACTIVE_RULE_QUOTA) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_QUOTA_EXCEEDED",
                    "A calendar node can have at most 8 active reminder rules");
        }
    }

    private void requireNewRelativeSemantics(
            CalendarTimeNodeEntity node,
            CalendarReminderOwnerKind ownerKind,
            Duration offset) {
        requireNewRelativeSemantics(node.getId(), ownerKind, offset);
    }

    private void requireNewRelativeSemantics(
            UUID nodeId,
            CalendarReminderOwnerKind ownerKind,
            Duration offset) {
        WorkspaceContext context = tenantContext();
        if (rules
                .existsByNodeIdAndOwnerKindAndRuleKindAndOffsetSecondsAndStatusAndWorkspaceIdAndCreatedByUserId(
                        nodeId,
                        ownerKind,
                        CalendarReminderRuleEntity.RuleKind.RELATIVE,
                        offset.getSeconds(),
                        CalendarReminderRuleEntity.Status.ACTIVE,
                        context.workspaceId(),
                        context.actorId())) {
            throw new BusinessException(
                    "DUPLICATE_CALENDAR_REMINDER",
                    "The same calendar reminder rule is already active");
        }
    }

    private void requireNewAbsoluteSemantics(
            CalendarTimeNodeEntity node,
            CalendarReminderOwnerKind ownerKind,
            Instant fireAt) {
        requireNewAbsoluteSemantics(node.getId(), ownerKind, fireAt);
    }

    private void requireNewAbsoluteSemantics(
            UUID nodeId,
            CalendarReminderOwnerKind ownerKind,
            Instant fireAt) {
        WorkspaceContext context = tenantContext();
        if (rules
                .existsByNodeIdAndOwnerKindAndRuleKindAndAbsoluteFireAtAndStatusAndWorkspaceIdAndCreatedByUserId(
                        nodeId,
                        ownerKind,
                        CalendarReminderRuleEntity.RuleKind.ABSOLUTE,
                        fireAt,
                        CalendarReminderRuleEntity.Status.ACTIVE,
                        context.workspaceId(),
                        context.actorId())) {
            throw new BusinessException(
                    "DUPLICATE_CALENDAR_REMINDER",
                    "The same calendar reminder rule is already active");
        }
    }

    private static CalendarReminderDeliveryMode requireDeliveryMode(
            CalendarTimeNodeEntity node,
            CalendarReminderDeliveryMode requested) {
        return requireDeliveryMode(node.getCriticality(), requested);
    }

    private static CalendarReminderDeliveryMode requireDeliveryMode(
            Criticality criticality,
            CalendarReminderDeliveryMode requested) {
        if (criticality == Criticality.CRITICAL && requested == null) {
            throw new BusinessException(
                    "CALENDAR_REMINDER_ACK_CHOICE_REQUIRED",
                    "Choose whether this critical reminder requires ACK");
        }
        return requested == null ? CalendarReminderDeliveryMode.ONCE : requested;
    }

    private void materializeFirstIfPersonal(
            CalendarReminderRuleEntity rule,
            long nodeRevision,
            Instant scheduledAt,
            Instant now) {
        if (rule.getOwnerKind() == CalendarReminderOwnerKind.PERSONAL) {
            occurrences.saveAndFlush(CalendarReminderOccurrenceEntity.pending(
                    UUID.randomUUID(), rule, nodeRevision, 0, scheduledAt, now));
        }
    }

    private void cancelPending(
            CalendarReminderRuleEntity rule,
            WorkspaceContext context,
            Instant now) {
        for (CalendarReminderOccurrenceEntity occurrence :
                occurrences.findAllByRuleIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                        rule.getId(),
                        CalendarReminderOccurrenceEntity.Status.PENDING,
                        context.workspaceId(),
                        context.actorId())) {
            occurrence.cancel(now);
        }
    }

    private CalendarReminderRuleEntity authorizedRule(
            UUID ruleId, WorkspaceContext context) {
        return rules.findByIdAndWorkspaceIdAndCreatedByUserId(
                        ruleId, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar reminder rule", "requested rule"));
    }

    private void publishCreated(
            CalendarReminderRuleEntity rule, String nodeLabel, Instant now) {
        events.publishEvent(new CalendarReminderLifecycleEvent(
                rule.getId(),
                CalendarReminderLifecycleEvent.Action.CREATED,
                nodeLabel,
                now));
    }

    private static Instant requireResolvedTime(CalendarTimeNodeEntity node) {
        if (node.getResolvedTime() == null) {
            throw new BusinessException(
                    "CALENDAR_NODE_TIME_UNRESOLVED",
                    "The calendar node time cannot be resolved safely");
        }
        return node.getResolvedTime();
    }

    private static void requireExpectedActiveNode(
            CalendarTimeNodeEntity node, long expectedNodeRevision) {
        if (node.getRevision() != expectedNodeRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar node changed; reload it before creating a reminder");
        }
        if (node.isCanceled()) {
            throw new BusinessException(
                    "CALENDAR_NODE_ALREADY_CANCELED",
                    "A canceled calendar node cannot have a new reminder");
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar reminder operations require a tenant workspace");
        }
        return context;
    }

    private record PersonalProjectionTarget(
            UUID planId,
            UUID nodeId,
            UUID snapshotId,
            UUID sourceOwnerId,
            Criticality criticality,
            Instant resolvedTime,
            long nodeRevision,
            String label) {}
}
