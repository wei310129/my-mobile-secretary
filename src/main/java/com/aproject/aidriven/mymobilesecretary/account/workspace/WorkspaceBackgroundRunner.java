package com.aproject.aidriven.mymobilesecretary.account.workspace;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUserStatus;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runs scheduled business work once per authorized workspace with failure isolation. */
@Component
public class WorkspaceBackgroundRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBackgroundRunner.class);

    private final WorkspaceMemberRepository memberRepository;

    public WorkspaceBackgroundRunner(WorkspaceMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public RunSummary forEachWorkspace(String jobName, Consumer<WorkspaceContext> operation) {
        String safeJobName = requireJobName(jobName);
        Objects.requireNonNull(operation, "operation");
        List<WorkspaceMember> memberships = runAuthentication(() ->
                memberRepository.findAllForUsersWithStatus(AppUserStatus.ACTIVE));
        Map<UUID, WorkspaceMember> targets = chooseMutationActor(memberships);
        int succeeded = 0;
        int failed = 0;
        for (WorkspaceMember target : targets.values()) {
            WorkspaceContext context = new WorkspaceContext(
                    target.getUserId(), target.getWorkspaceId(), WorkspaceChannel.BACKGROUND);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                RequestCorrelationContext.run(UUID.randomUUID(), () -> {
                    operation.accept(context);
                    return null;
                });
                succeeded++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Workspace background job failed [job={}, workspace={}, cause={}]",
                        safeJobName,
                        SensitiveValueFingerprint.of(target.getWorkspaceId().toString()),
                        failure.getClass().getSimpleName());
            }
        }
        int workspaceCount = memberships.stream()
                .map(WorkspaceMember::getWorkspaceId)
                .collect(java.util.stream.Collectors.toSet())
                .size();
        return new RunSummary(workspaceCount, succeeded, failed, workspaceCount - targets.size());
    }

    /** Runs actor-private background work once for every active member with mutation rights. */
    public ActorRunSummary forEachActor(String jobName, Consumer<WorkspaceContext> operation) {
        String safeJobName = requireJobName(jobName);
        Objects.requireNonNull(operation, "operation");
        List<WorkspaceMember> targets = runAuthentication(() ->
                memberRepository.findAllForUsersWithStatus(AppUserStatus.ACTIVE)).stream()
                .filter(member -> member.grants(WorkspaceRole.MEMBER))
                .collect(java.util.stream.Collectors.toMap(
                        member -> member.getWorkspaceId() + ":" + member.getUserId(),
                        member -> member, (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
        int succeeded = 0;
        int failed = 0;
        for (WorkspaceMember target : targets) {
            WorkspaceContext context = new WorkspaceContext(
                    target.getUserId(), target.getWorkspaceId(), WorkspaceChannel.BACKGROUND);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                RequestCorrelationContext.run(UUID.randomUUID(), () -> {
                    operation.accept(context);
                    return null;
                });
                succeeded++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Actor background job failed [job={}, workspace={}, actor={}, cause={}]",
                        safeJobName,
                        SensitiveValueFingerprint.of(target.getWorkspaceId().toString()),
                        SensitiveValueFingerprint.of(target.getUserId().toString()),
                        failure.getClass().getSimpleName());
            }
        }
        return new ActorRunSummary(targets.size(), succeeded, failed);
    }

    /** Executes global account/retention work under a NIL tenant that cannot expose business rows. */
    public <T> T runSystem(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                WorkspaceContext.system())) {
            return operation.get();
        }
    }

    private static <T> T runAuthentication(Supplier<T> operation) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                WorkspaceContext.authentication())) {
            return operation.get();
        }
    }

    private static Map<UUID, WorkspaceMember> chooseMutationActor(
            List<WorkspaceMember> memberships) {
        Map<UUID, WorkspaceMember> targets = new LinkedHashMap<>();
        for (WorkspaceMember membership : memberships) {
            if (!membership.grants(WorkspaceRole.MEMBER)) {
                continue;
            }
            targets.merge(membership.getWorkspaceId(), membership,
                    (current, candidate) -> candidate.grants(current.getRole())
                            ? candidate : current);
        }
        return targets;
    }

    private static String requireJobName(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName is required");
        }
        String stripped = jobName.strip();
        return stripped.length() <= 80 ? stripped : stripped.substring(0, 80);
    }

    public record RunSummary(int workspaces, int succeeded, int failed, int skipped) {
    }

    public record ActorRunSummary(int actors, int succeeded, int failed) {
    }
}
