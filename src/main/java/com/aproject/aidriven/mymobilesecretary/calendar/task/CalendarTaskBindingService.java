package com.aproject.aidriven.mymobilesecretary.calendar.task;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarTaskBindingService {

    private final CalendarTaskBindingRepository bindings;
    private final TaskRepository tasks;
    private final CalendarPlanRepository plans;
    private final CalendarActivityRepository activities;
    private final CalendarTimeNodeRepository nodes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CalendarTaskBindingService(
            CalendarTaskBindingRepository bindings,
            TaskRepository tasks,
            CalendarPlanRepository plans,
            CalendarActivityRepository activities,
            CalendarTimeNodeRepository nodes,
            ApplicationEventPublisher events,
            Clock clock) {
        this.bindings = bindings;
        this.tasks = tasks;
        this.plans = plans;
        this.activities = activities;
        this.nodes = nodes;
        this.events = events;
        this.clock = clock;
    }

    public CalendarTaskBindingView bind(
            String requestKey, long taskId, CalendarTaskTarget target) {
        WorkspaceContext context = tenantContext();
        tasks.findByIdAndCreatedByUserId(taskId, context.actorId())
                .orElseThrow(() -> new NotFoundException("Task", "requested task"));
        authorizeTarget(target, context);

        String requestHash = hash(requireRequestKey(requestKey));
        String payloadHash = hash(taskId + "|" + target);
        CalendarTaskBindingEntity replay = bindings
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        requestHash, context.workspaceId(), context.actorId())
                .orElse(null);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return view(replay);
        }
        CalendarTaskBindingEntity existing = bindings
                .findByTaskIdAndWorkspaceIdAndCreatedByUserId(
                        taskId, context.workspaceId(), context.actorId())
                .orElse(null);
        if (existing != null) {
            if (sameTarget(existing, target)) {
                return view(existing);
            }
            throw new BusinessException(
                    "CALENDAR_TASK_ALREADY_BOUND",
                    "Task is already bound to a different calendar target");
        }

        Instant now = Instant.now(clock);
        int inserted = bindings.insertIfAbsent(
                UUID.randomUUID(),
                taskId,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                requestHash,
                payloadHash,
                now,
                context.workspaceId(),
                context.actorId());
        CalendarTaskBindingEntity result = bindings
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        requestHash, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new IllegalStateException(
                        "Calendar task binding arbitration produced no row"));
        requireSamePayload(result, payloadHash);
        if (inserted == 1) {
            events.publishEvent(new CalendarTaskBoundEvent(taskId, target.kind(), now));
        }
        return view(result);
    }

    @Transactional(readOnly = true)
    public CalendarTaskBindingView getForTask(long taskId) {
        WorkspaceContext context = tenantContext();
        return bindings
                .findByTaskIdAndWorkspaceIdAndCreatedByUserId(
                        taskId, context.workspaceId(), context.actorId())
                .map(CalendarTaskBindingService::view)
                .orElseThrow(() -> new NotFoundException(
                        "Calendar task binding", "requested task"));
    }

    @Transactional(readOnly = true)
    public CalendarTaskBindingView findByRequestKey(String requestKey) {
        WorkspaceContext context = tenantContext();
        return bindings
                .findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                        hash(requireRequestKey(requestKey)),
                        context.workspaceId(),
                        context.actorId())
                .map(CalendarTaskBindingService::view)
                .orElse(null);
    }

    private void authorizeTarget(CalendarTaskTarget target, WorkspaceContext context) {
        plans.findByIdAndWorkspaceIdAndCreatedByUserId(
                        target.planId(), context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException("Calendar plan", "requested plan"));
        if (target.kind() == CalendarTaskTarget.TargetKind.ACTIVITY) {
            var activity = activities
                    .findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.activityId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar activity", "requested activity"));
            if (!activity.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException("Calendar activity belongs to another plan");
            }
        }
        if (target.kind() == CalendarTaskTarget.TargetKind.NODE) {
            var node = nodes.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.nodeId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar node", "requested node"));
            if (!node.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException("Calendar node belongs to another plan");
            }
        }
    }

    private static boolean sameTarget(
            CalendarTaskBindingEntity binding, CalendarTaskTarget target) {
        return binding.getTargetKind() == target.kind()
                && binding.getPlanId().equals(target.planId())
                && Objects.equals(binding.getActivityId(), target.activityId())
                && Objects.equals(binding.getNodeId(), target.nodeId());
    }

    private static void requireSamePayload(
            CalendarTaskBindingEntity binding, String payloadHash) {
        if (!binding.getCreationPayloadHash().equals(payloadHash)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for a different calendar task binding");
        }
    }

    private static CalendarTaskBindingView view(CalendarTaskBindingEntity binding) {
        return new CalendarTaskBindingView(binding.getTaskId(), binding.getTargetKind());
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar task binding requires a tenant workspace");
        }
        return context;
    }

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
}
