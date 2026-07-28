package com.aproject.aidriven.mymobilesecretary.project.calendar;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanIdentityView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScope;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScopedTransactionExecutor;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectCalendarPlanService {

    private final ProjectScopedTransactionExecutor transactions;
    private final ProjectCalendarPlanBindingRepository bindings;
    private final CalendarApplicationService calendars;
    private final Clock clock;

    public ProjectCalendarPlanService(
            ProjectScopedTransactionExecutor transactions,
            ProjectCalendarPlanBindingRepository bindings,
            CalendarApplicationService calendars,
            Clock clock) {
        this.transactions = transactions;
        this.bindings = bindings;
        this.calendars = calendars;
        this.clock = clock;
    }

    public ProjectCalendarPlanView createAndBind(
            ProjectScope scope, CreateProjectCalendarPlanCommand command) {
        return transactions.create(scope, authorized -> {
            String requestHash = hash(command.requestKey());
            CreateCalendarPlanCommand calendarCommand =
                    calendarCommand(authorized.projectId(), command);
            String payloadHash =
                    hash(authorized.projectId() + "|" + calendarCommand);
            ProjectCalendarPlanBinding replay =
                    bindings.findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                                    requestHash,
                                    authorized.workspaceId(),
                                    authorized.actorId())
                            .orElse(null);
            if (replay != null) {
                replay.requireCreationReplay(
                        authorized.projectId(),
                        replay.getPlanId(),
                        payloadHash);
                return view(replay, calendars.getPlanById(replay.getPlanId()));
            }

            CalendarPlanIdentityView plan =
                    calendars.createPlanWithIdentity(calendarCommand);
            plan = calendars.lockActivePlanById(plan.planId());
            insert(
                    authorized.projectId(),
                    plan.planId(),
                    requestHash,
                    payloadHash,
                    authorized.workspaceId(),
                    authorized.actorId());
            ProjectCalendarPlanBinding binding =
                    bindings.findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                                    requestHash,
                                    authorized.workspaceId(),
                                    authorized.actorId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Project Calendar binding arbitration produced no row"));
            binding.requireCreationReplay(
                    authorized.projectId(), plan.planId(), payloadHash);
            return view(binding, plan);
        });
    }

    public ProjectCalendarPlanView bindExisting(
            ProjectScope scope,
            String requestKey,
            UUID planId,
            long expectedPlanRevision) {
        return transactions.create(scope, authorized -> {
            CalendarPlanIdentityView plan = calendars.lockActivePlanById(planId);
            if (expectedPlanRevision <= 0
                    || plan.revision() != expectedPlanRevision) {
                throw stalePlan();
            }
            String requestHash = hash(requireRequestKey(requestKey));
            String payloadHash = hash(
                    authorized.projectId() + "|" + planId + "|" + expectedPlanRevision);
            insert(
                    authorized.projectId(),
                    planId,
                    requestHash,
                    payloadHash,
                    authorized.workspaceId(),
                    authorized.actorId());
            ProjectCalendarPlanBinding binding =
                    bindings.findByCreationRequestHashAndWorkspaceIdAndCreatedByUserId(
                                    requestHash,
                                    authorized.workspaceId(),
                                    authorized.actorId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Project Calendar binding arbitration produced no row"));
            binding.requireCreationReplay(
                    authorized.projectId(), planId, payloadHash);
            return view(binding, plan);
        });
    }

    public ProjectCalendarPlanView get(ProjectScope scope, UUID bindingId) {
        return transactions.query(scope, authorized -> {
            ProjectCalendarPlanBinding binding = binding(
                    bindingId,
                    authorized.projectId(),
                    authorized.workspaceId(),
                    authorized.actorId());
            return view(binding, calendars.getPlanById(binding.getPlanId()));
        });
    }

    public List<ProjectCalendarPlanView> list(ProjectScope scope) {
        return transactions.query(scope, authorized -> bindings
                .findAllByProjectIdAndWorkspaceIdAndCreatedByUserIdOrderByUpdatedAtDesc(
                        authorized.projectId(),
                        authorized.workspaceId(),
                        authorized.actorId())
                .stream()
                .map(binding -> view(
                        binding, calendars.getPlanById(binding.getPlanId())))
                .toList());
    }

    public ProjectCalendarPlanView changeCategory(
            ProjectScope scope,
            UUID bindingId,
            String category,
            long expectedPlanRevision) {
        return transactions.update(scope, scope.projectId(), authorized -> {
            ProjectCalendarPlanBinding candidate = binding(
                    bindingId,
                    authorized.projectId(),
                    authorized.workspaceId(),
                    authorized.actorId());
            if (candidate.getStatus() != ProjectCalendarBindingStatus.ACTIVE) {
                throw inactiveBinding();
            }
            CalendarPlanIdentityView plan = calendars.changePlanCategoryById(
                    candidate.getPlanId(), category, expectedPlanRevision);
            ProjectCalendarPlanBinding binding = activeBinding(
                    bindingId,
                    authorized.projectId(),
                    authorized.workspaceId(),
                    authorized.actorId());
            return view(binding, plan);
        });
    }

    public ProjectCalendarPlanView unlink(
            ProjectScope scope,
            UUID bindingId,
            long expectedBindingRevision,
            String requestKey) {
        return transactions.delete(scope, scope.projectId(), authorized -> {
            ProjectCalendarPlanBinding binding = bindings
                    .findWithLockByIdAndProjectIdAndWorkspaceIdAndCreatedByUserId(
                            bindingId,
                            authorized.projectId(),
                            authorized.workspaceId(),
                            authorized.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Project Calendar binding", "requested binding"));
            String requestHash = hash(requireRequestKey(requestKey));
            String payloadHash = hash(bindingId + "|" + expectedBindingRevision);
            binding.unlinkExplicitly(
                    expectedBindingRevision,
                    requestHash,
                    payloadHash,
                    now());
            bindings.saveAndFlush(binding);
            return view(binding, calendars.getPlanById(binding.getPlanId()));
        });
    }

    private void insert(
            UUID projectId,
            UUID planId,
            String requestHash,
            String payloadHash,
            UUID workspaceId,
            UUID actorId) {
        int inserted = bindings.insertIfAbsent(
                UUID.randomUUID(),
                projectId,
                planId,
                requestHash,
                payloadHash,
                now(),
                workspaceId,
                actorId);
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException(
                    "Project Calendar binding arbitration returned an invalid count");
        }
    }

    private ProjectCalendarPlanBinding binding(
            UUID bindingId, UUID projectId, UUID workspaceId, UUID actorId) {
        return bindings
                .findByIdAndProjectIdAndWorkspaceIdAndCreatedByUserId(
                        bindingId, projectId, workspaceId, actorId)
                .orElseThrow(() -> new NotFoundException(
                        "Project Calendar binding", "requested binding"));
    }

    private ProjectCalendarPlanBinding activeBinding(
            UUID bindingId, UUID projectId, UUID workspaceId, UUID actorId) {
        ProjectCalendarPlanBinding binding = bindings
                .findWithLockByIdAndProjectIdAndWorkspaceIdAndCreatedByUserId(
                        bindingId, projectId, workspaceId, actorId)
                .orElseThrow(() -> new NotFoundException(
                        "Project Calendar binding", "requested binding"));
        if (binding.getStatus() != ProjectCalendarBindingStatus.ACTIVE) {
            throw inactiveBinding();
        }
        return binding;
    }

    private static ProjectCalendarPlanView view(
            ProjectCalendarPlanBinding binding,
            CalendarPlanIdentityView plan) {
        return new ProjectCalendarPlanView(
                binding.getId(),
                binding.getProjectId(),
                binding.getPlanId(),
                binding.getStatus(),
                binding.getUnlinkReason(),
                binding.getBindingRevision(),
                plan.title(),
                plan.category(),
                plan.onlineHost(),
                plan.status(),
                plan.revision(),
                binding.getUnlinkedAt());
    }

    private static CreateCalendarPlanCommand calendarCommand(
            UUID projectId, CreateProjectCalendarPlanCommand command) {
        CreateCalendarPlanCommand source = command.calendar();
        return new CreateCalendarPlanCommand(
                hash(projectId + "|" + command.requestKey()),
                source.title(),
                source.placement(),
                source.category(),
                source.onlineLink(),
                source.onlineLinkLabel(),
                source.activities(),
                source.nodes());
    }

    private static BusinessException inactiveBinding() {
        return new BusinessException(
                "PROJECT_CALENDAR_BINDING_INACTIVE",
                "Project Calendar ownership is no longer active");
    }

    private static BusinessException stalePlan() {
        return new BusinessException(
                "STALE_CALENDAR_REVISION",
                "Calendar plan changed; reload before binding it");
    }

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException(
                    "A bounded Project Calendar request key is required");
        }
        return value.strip();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
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
