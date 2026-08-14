package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeResolver;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarOnlineAccessLinkEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarOnlineAccessLinkRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarApplicationService {

    private final CalendarPlanRepository plans;
    private final CalendarActivityRepository activities;
    private final CalendarTimeNodeRepository nodes;
    private final CalendarOnlineAccessLinkRepository links;
    private final CalendarSourceMutationSignalService sourceMutationSignals;
    private final CalendarEffectiveOwnerAccess effectiveOwnerAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CalendarApplicationService(
            CalendarPlanRepository plans,
            CalendarActivityRepository activities,
            CalendarTimeNodeRepository nodes,
            CalendarOnlineAccessLinkRepository links,
            CalendarSourceMutationSignalService sourceMutationSignals,
            CalendarEffectiveOwnerAccess effectiveOwnerAccess,
            ApplicationEventPublisher events,
            Clock clock) {
        this.plans = plans;
        this.activities = activities;
        this.nodes = nodes;
        this.links = links;
        this.sourceMutationSignals = sourceMutationSignals;
        this.effectiveOwnerAccess = effectiveOwnerAccess;
        this.events = events;
        this.clock = clock;
    }

    public CalendarPlanView createPlan(CreateCalendarPlanCommand command) {
        WorkspaceContext context = tenantContext();
        String requestHash = hash(requireRequestKey(command.requestKey()));
        String payloadHash = hash(command.toString());
        CalendarOnlineLink onlineLink =
                CalendarOnlineLink.parse(command.onlineLink(), command.onlineLinkLabel());
        String category = CalendarTextNormalizer.optionalCategory(command.category());
        validateNodes(command.nodes(), command.placement());
        for (CalendarActivityDraft activity : command.activities()) {
            validateNodes(activity.nodes(), activity.placement());
        }

        CalendarPlanEntity existing = plans
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        requestHash, context.workspaceId(), context.actorId())
                .orElse(null);
        if (existing != null) {
            CalendarEffectiveOwnerAccess.Scope scope =
                    effectiveOwnerAccess.require(existing.getId(), context);
            if (!payloadHash.equals(existing.getCreationPayloadHash())) {
                throw new BusinessException(
                        "IDEMPOTENCY_CONFLICT",
                        "The request key was already used for a different calendar payload");
            }
            return view(existing, context, scope.sourceOwnerId());
        }

        Instant now = Instant.now(clock);
        PlacementColumns placement = PlacementColumns.from(command.placement());
        int inserted = plans.insertIfAbsent(
                UUID.randomUUID(),
                command.title().strip(),
                placement.kind(),
                placement.timedStart(),
                placement.timedEnd(),
                placement.zoneId(),
                placement.allDayStart(),
                placement.allDayEndExclusive(),
                requestHash,
                payloadHash,
                category,
                now,
                context.workspaceId(),
                context.actorId());
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException(
                    "Calendar creation arbitration returned an invalid count");
        }
        CalendarPlanEntity plan = plans
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        requestHash, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new IllegalStateException(
                        "Calendar creation arbitration did not produce a plan"));
        if (!payloadHash.equals(plan.getCreationPayloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for a different calendar payload");
        }
        if (inserted == 0) {
            CalendarEffectiveOwnerAccess.Scope scope =
                    effectiveOwnerAccess.require(plan.getId(), context);
            return view(plan, context, scope.sourceOwnerId());
        }
        for (CalendarActivityDraft draft : command.activities()) {
            CalendarActivityEntity activity = activities.saveAndFlush(CalendarActivityEntity.create(
                    UUID.randomUUID(),
                    plan.getId(),
                    draft.title(),
                    draft.placement(),
                    CalendarTextNormalizer.optionalCategory(draft.category()),
                    now));
            persistNodes(
                    plan.getId(), activity.getId(), draft.nodes(), draft.placement(), now);
        }
        persistNodes(plan.getId(), null, command.nodes(), command.placement(), now);
        if (onlineLink != null) {
            links.saveAndFlush(CalendarOnlineAccessLinkEntity.forPlan(
                    UUID.randomUUID(),
                    plan.getId(),
                    onlineLink.normalizedUri(),
                    onlineLink.safeHost(),
                    onlineLink.label(),
                    now));
        }
        events.publishEvent(new CalendarPlanCreatedEvent(
                plan.getId(), plan.getTitle(), now));
        return new CalendarPlanView(plan.getTitle(), plan.getCategory(),
                onlineLink == null ? null : onlineLink.safeHost(), 1);
    }

    public CalendarPlanIdentityView createPlanWithIdentity(
            CreateCalendarPlanCommand command) {
        createPlan(command);
        WorkspaceContext context = tenantContext();
        CalendarPlanEntity plan = plans
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        hash(requireRequestKey(command.requestKey())),
                        context.workspaceId(),
                        context.actorId())
                .orElseThrow(() -> new IllegalStateException(
                        "Calendar creation did not produce an identity"));
        return identityView(plan, context, context.actorId());
    }

    public CalendarNodeRevisionView moveNodeOrdinarily(
            String requestKey, String nodeKey, Instant time, long expectedRevision) {
        CalendarTimeNodeEntity node = authorizedNode(requestKey, nodeKey);
        long previousRevision = node.getRevision();
        Instant now = Instant.now(clock);
        node.moveOrdinarily(time, expectedRevision, now);
        nodes.saveAndFlush(node);
        sourceMutationSignals.recordOwnerGeneral(
                node,
                CalendarSourceMutationSignalService.MutationKind.NODE_TIME,
                now);
        events.publishEvent(new CalendarNodeRevisedEvent(
                node.getId(),
                node.getLabel(),
                previousRevision,
                node.getRevision(),
                node.getResolvedTime(),
                now));
        return revisionView(node);
    }

    public CalendarNodeRevisionView reviseLockedNode(
            String requestKey, String nodeKey, Instant time, long expectedRevision) {
        CalendarTimeNodeEntity node = authorizedNode(requestKey, nodeKey);
        long previousRevision = node.getRevision();
        Instant now = Instant.now(clock);
        node.reviseAbsolute(time, expectedRevision, now);
        nodes.saveAndFlush(node);
        sourceMutationSignals.recordOwnerGeneral(
                node,
                CalendarSourceMutationSignalService.MutationKind.NODE_TIME,
                now);
        events.publishEvent(new CalendarNodeRevisedEvent(
                node.getId(),
                node.getLabel(),
                previousRevision,
                node.getRevision(),
                node.getResolvedTime(),
                now));
        return revisionView(node);
    }

    @Transactional(readOnly = true)
    public CalendarPlanView getPlan(String requestKey) {
        WorkspaceContext context = tenantContext();
        AuthorizedPlan authorized = authorizedPlan(requestKey, context, false);
        return view(authorized.plan(), context, authorized.scope().sourceOwnerId());
    }

    @Transactional(readOnly = true)
    public CalendarPlanIdentityView getPlanById(UUID planId) {
        WorkspaceContext context = tenantContext();
        CalendarEffectiveOwnerAccess.Scope scope =
                effectiveOwnerAccess.require(planId, context);
        CalendarPlanEntity plan = plans
                .findByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        return identityView(plan, context, scope.sourceOwnerId());
    }

    public CalendarPlanIdentityView lockActivePlanById(UUID planId) {
        WorkspaceContext context = tenantContext();
        CalendarEffectiveOwnerAccess.Scope scope =
                effectiveOwnerAccess.requireAndLockPlan(planId, context);
        CalendarPlanEntity plan = plans
                .findByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        return identityView(plan, context, scope.sourceOwnerId());
    }

    public CalendarPlanView setPlanOnlineLink(
            String requestKey, String rawUri, String label) {
        CalendarOnlineLink link = CalendarOnlineLink.parse(rawUri, label);
        WorkspaceContext context = tenantContext();
        AuthorizedPlan authorized = authorizedPlan(requestKey, context, true);
        CalendarPlanEntity plan = authorized.plan();
        plan.requireActiveForMutation();
        if (links.findByPlanIdAndActivityIdIsNullAndNodeIdIsNullAndWorkspaceIdAndCreatedByUserId(
                        plan.getId(),
                        context.workspaceId(),
                        authorized.scope().sourceOwnerId())
                .isPresent()) {
            throw new BusinessException(
                    "CALENDAR_ONLINE_LINK_EXISTS",
                    "This calendar target already has a primary online link");
        }
        int inserted = links.insertPlanLink(
                UUID.randomUUID(),
                plan.getId(),
                link.normalizedUri(),
                link.safeHost(),
                link.label(),
                Instant.now(clock),
                context.workspaceId(),
                authorized.scope().sourceOwnerId());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Calendar online link insertion returned an invalid count");
        }
        return view(plan, context, authorized.scope().sourceOwnerId());
    }

    public CalendarPlanView changePlanCategory(
            String requestKey, String category, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        AuthorizedPlan authorized = authorizedPlan(requestKey, context, true);
        CalendarPlanEntity plan = authorized.plan();
        plan.changeCategory(
                CalendarTextNormalizer.optionalCategory(category),
                expectedRevision,
                Instant.now(clock));
        plans.saveAndFlush(plan);
        return view(plan, context, authorized.scope().sourceOwnerId());
    }

    public CalendarPlanIdentityView changePlanCategoryById(
            UUID planId, String category, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        CalendarEffectiveOwnerAccess.Scope scope =
                effectiveOwnerAccess.requireAndLockPlan(planId, context);
        CalendarPlanEntity plan = plans
                .findByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.changeCategory(
                CalendarTextNormalizer.optionalCategory(category),
                expectedRevision,
                Instant.now(clock));
        plans.saveAndFlush(plan);
        return identityView(plan, context, scope.sourceOwnerId());
    }

    public CalendarNodeRevisionView reviseNodeLocation(
            String requestKey,
            String nodeKey,
            CalendarLocation location,
            long expectedRevision) {
        CalendarTimeNodeEntity node = authorizedNode(requestKey, nodeKey);
        Instant now = Instant.now(clock);
        node.reviseLocation(location, expectedRevision, now);
        nodes.saveAndFlush(node);
        sourceMutationSignals.recordOwnerGeneral(
                node,
                CalendarSourceMutationSignalService.MutationKind.NODE_LOCATION,
                now);
        events.publishEvent(new CalendarNodeLocationRevisedEvent(
                node.getId(), node.getLabel(), node.getRevision(), now));
        return revisionView(node);
    }

    public CalendarNodeRevisionView cancelNodeOrdinarily(
            String requestKey,
            String nodeKey,
            long expectedRevision) {
        CalendarTimeNodeEntity node =
                authorizedNode(requestKey, nodeKey);
        Instant now = Instant.now(clock);
        node.cancel(expectedRevision, now);
        nodes.saveAndFlush(node);
        sourceMutationSignals.recordOwnerGeneral(
                node,
                CalendarSourceMutationSignalService.MutationKind
                        .NODE_CANCELLATION,
                now);
        events.publishEvent(new CalendarNodeCanceledEvent(
                node.getId(),
                node.getLabel(),
                node.getRevision(),
                now));
        return revisionView(node);
    }

    public CalendarTimeNodeEntity addAbsoluteNode(
            UUID planId, String nodeKey, String label, Instant time) {
        WorkspaceContext context = tenantContext();
        CalendarEffectiveOwnerAccess.Scope scope =
                effectiveOwnerAccess.requireAndLockPlan(planId, context);
        CalendarPlanEntity plan = plans.findByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        if (nodes.findByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                        planId, nodeKey, context.workspaceId(), scope.sourceOwnerId())
                .isPresent()) {
            throw new BusinessException(
                    "CALENDAR_NODE_KEY_EXISTS",
                    "This calendar plan already has the requested node key");
        }
        Instant now = Instant.now(clock);
        UUID nodeId = UUID.randomUUID();
        int inserted = nodes.insertAbsolute(
                nodeId,
                planId,
                nodeKey,
                label,
                time,
                now,
                context.workspaceId(),
                scope.sourceOwnerId());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Calendar node insertion returned an invalid count");
        }
        CalendarTimeNodeEntity node = nodes
                .findByIdAndWorkspaceIdAndCreatedByUserId(
                        nodeId, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Calendar node insertion did not produce a node"));
        events.publishEvent(new CalendarNodeCreatedEvent(
                node.getId(), node.getLabel(), node.getResolvedTime(), now));
        return node;
    }

    public CalendarTimeNodeEntity lockNodeForReminder(
            String requestKey, String nodeKey) {
        WorkspaceContext context = tenantContext();
        AuthorizedPlan authorized = authorizedPlan(requestKey, context, true);
        CalendarPlanEntity plan = authorized.plan();
        plan.requireActiveForMutation();
        CalendarTimeNodeEntity node = nodes
                .findWithLockByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                        plan.getId(),
                        nodeKey,
                        context.workspaceId(),
                        authorized.scope().sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar node", "requested node"));
        if (node.isCanceled()) {
            throw new BusinessException(
                    "CALENDAR_NODE_ALREADY_CANCELED",
                    "A canceled calendar node cannot be used for a reminder");
        }
        return node;
    }

    private void persistNodes(
            UUID planId,
            UUID activityId,
            List<CalendarNodeDraft> drafts,
            CalendarPlacement placement,
            Instant now) {
        Map<String, Instant> resolved = resolveNodes(drafts, placement);
        for (CalendarNodeDraft draft : drafts) {
            CalendarTimeNodeEntity node = CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    planId,
                    activityId,
                    draft.node(),
                    resolved.get(draft.node().id()),
                    now);
            if (draft.location() != null) {
                node.reviseLocation(draft.location(), node.getRevision(), now);
            }
            nodes.saveAndFlush(node);
        }
    }

    private static Map<String, Instant> resolveNodes(
            List<CalendarNodeDraft> drafts, CalendarPlacement placement) {
        if (drafts.isEmpty()) {
            return Map.of();
        }
        Instant start;
        Instant end;
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            start = interval.start();
            end = interval.end();
        } else if (placement instanceof CalendarPlacement.TimedPoint point) {
            start = point.time();
            end = point.time();
        } else {
            throw new IllegalArgumentException(
                    "All-day owners require absolute all-day nodes");
        }
        return CalendarTimeResolver.resolve(
                drafts.stream().map(CalendarNodeDraft::node).toList(), start, end);
    }

    private CalendarTimeNodeEntity authorizedNode(String requestKey, String nodeKey) {
        WorkspaceContext context = tenantContext();
        AuthorizedPlan authorized = authorizedPlan(requestKey, context, true);
        CalendarPlanEntity plan = authorized.plan();
        plan.requireActiveForMutation();
        return nodes.findByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                        plan.getId(),
                        nodeKey,
                        context.workspaceId(),
                        authorized.scope().sourceOwnerId())
                .orElseThrow(() -> new NotFoundException("Calendar node", "requested node"));
    }

    private AuthorizedPlan authorizedPlan(
            String requestKey, WorkspaceContext context, boolean lock) {
        String requestHash = hash(requireRequestKey(requestKey));
        CalendarEffectiveOwnerAccess.Scope scope = lock
                ? effectiveOwnerAccess.requireAndLockPlanByCreationRequestHash(
                        requestHash, context)
                : effectiveOwnerAccess.requireByCreationRequestHash(requestHash, context);
        CalendarPlanEntity plan = plans
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        requestHash, context.workspaceId(), scope.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException("Calendar plan", "requested plan"));
        return new AuthorizedPlan(plan, scope);
    }

    private CalendarPlanView view(
            CalendarPlanEntity plan, WorkspaceContext context, UUID sourceOwnerId) {
        String host = links
                .findByPlanIdAndActivityIdIsNullAndNodeIdIsNullAndWorkspaceIdAndCreatedByUserId(
                        plan.getId(), context.workspaceId(), sourceOwnerId)
                .map(CalendarOnlineAccessLinkEntity::getSafeHost)
                .orElse(null);
        return new CalendarPlanView(
                plan.getTitle(), plan.getCategory(), host, plan.getRevision());
    }

    private CalendarPlanIdentityView identityView(
            CalendarPlanEntity plan, WorkspaceContext context, UUID sourceOwnerId) {
        String host = links
                .findByPlanIdAndActivityIdIsNullAndNodeIdIsNullAndWorkspaceIdAndCreatedByUserId(
                        plan.getId(), context.workspaceId(), sourceOwnerId)
                .map(CalendarOnlineAccessLinkEntity::getSafeHost)
                .orElse(null);
        return new CalendarPlanIdentityView(
                plan.getId(),
                plan.getTitle(),
                plan.getCategory(),
                host,
                plan.getStatus(),
                plan.getRevision());
    }

    private static void validateNodes(
            List<CalendarNodeDraft> drafts, CalendarPlacement placement) {
        if (drafts.isEmpty()) {
            return;
        }
        Instant start;
        Instant end;
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            start = interval.start();
            end = interval.end();
        } else if (placement instanceof CalendarPlacement.TimedPoint point) {
            start = point.time();
            end = point.time();
        } else {
            throw new IllegalArgumentException("All-day owners require absolute all-day nodes");
        }
        CalendarTimeResolver.resolve(
                drafts.stream().map(CalendarNodeDraft::node).toList(), start, end);
    }

    private static CalendarNodeRevisionView revisionView(CalendarTimeNodeEntity node) {
        return new CalendarNodeRevisionView(
                node.getNodeKey(), node.getRevision(), node.getAbsoluteTime());
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar operations require a tenant workspace");
        }
        return context;
    }

    private record AuthorizedPlan(
            CalendarPlanEntity plan, CalendarEffectiveOwnerAccess.Scope scope) {}

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
        return value.strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record PlacementColumns(
            String kind,
            Instant timedStart,
            Instant timedEnd,
            String zoneId,
            LocalDate allDayStart,
            LocalDate allDayEndExclusive) {

        private static PlacementColumns from(CalendarPlacement placement) {
            if (placement instanceof CalendarPlacement.TimedInterval interval) {
                return new PlacementColumns(
                        CalendarPlacement.Kind.TIMED_INTERVAL.name(),
                        interval.start(),
                        interval.end(),
                        interval.zoneId().getId(),
                        null,
                        null);
            }
            if (placement instanceof CalendarPlacement.TimedPoint point) {
                return new PlacementColumns(
                        CalendarPlacement.Kind.TIMED_POINT.name(),
                        point.time(),
                        null,
                        point.zoneId().getId(),
                        null,
                        null);
            }
            CalendarPlacement.AllDay allDay = (CalendarPlacement.AllDay) placement;
            return new PlacementColumns(
                    CalendarPlacement.Kind.ALL_DAY.name(),
                    null,
                    null,
                    null,
                    allDay.start(),
                    allDay.endExclusive());
        }
    }
}
