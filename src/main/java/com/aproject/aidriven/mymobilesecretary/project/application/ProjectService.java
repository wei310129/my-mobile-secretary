package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, ApplicationEventPublisher events, Clock clock) {
        this.projects = projects;
        this.events = events;
        this.clock = clock;
    }

    public Project createProject(ProjectType type, String name, String creationRequestHmac) {
        WorkspaceContext context = tenantContext();
        Instant now = Instant.now(clock);
        Project candidate = Project.create(type, name, creationRequestHmac, now);
        Project existing = projects
                .findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
                        context.workspaceId(), context.actorId(), candidate.getCreationRequestHmac())
                .orElse(null);
        if (existing != null) {
            return requireMatchingReplay(existing, type, name);
        }

        int inserted = projects.insertIfAbsent(candidate.getId(), candidate.getType().name(),
                candidate.getName(), candidate.getCreationRequestHmac(), now,
                context.workspaceId(), context.actorId());
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException("Project creation arbitration returned an invalid count");
        }
        Project persisted = projects
                .findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
                        context.workspaceId(), context.actorId(), candidate.getCreationRequestHmac())
                .orElseThrow(() -> new IllegalStateException(
                        "Project creation arbitration did not produce a project"));
        requireMatchingReplay(persisted, type, name);
        if (inserted == 1) {
            publish(persisted, ProjectLifecycleEvent.Action.CREATED, now);
        }
        return persisted;
    }

    @Transactional(readOnly = true)
    public Project getProject(UUID projectId) {
        return authorizedProject(projectId);
    }

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        WorkspaceContext context = tenantContext();
        return projects.findAllByWorkspaceIdAndCreatedByUserIdOrderByUpdatedAtDesc(
                context.workspaceId(), context.actorId());
    }

    @Transactional(readOnly = true)
    public ProjectDisplay display(UUID projectId) {
        return ProjectDisplay.from(authorizedProject(projectId));
    }

    public Project renameProject(UUID projectId, String name) {
        Project project = authorizedProject(projectId);
        Instant now = Instant.now(clock);
        project.rename(name, now);
        projects.save(project);
        publish(project, ProjectLifecycleEvent.Action.RENAMED, now);
        return project;
    }

    public Project completeProject(UUID projectId) {
        Project project = authorizedProject(projectId);
        Instant now = Instant.now(clock);
        project.complete(now);
        projects.save(project);
        publish(project, ProjectLifecycleEvent.Action.COMPLETED, now);
        return project;
    }

    public Project reopenProject(UUID projectId) {
        Project project = authorizedProject(projectId);
        Instant now = Instant.now(clock);
        project.reopen(now);
        projects.save(project);
        publish(project, ProjectLifecycleEvent.Action.REOPENED, now);
        return project;
    }

    public Project archiveProject(UUID projectId) {
        Project project = authorizedProject(projectId);
        Instant now = Instant.now(clock);
        project.archive(now);
        projects.save(project);
        publish(project, ProjectLifecycleEvent.Action.ARCHIVED, now);
        return project;
    }

    private Project authorizedProject(UUID projectId) {
        WorkspaceContext context = tenantContext();
        return projects.findByIdAndWorkspaceIdAndCreatedByUserId(
                        projectId, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException("Project", "requested project"));
    }

    private static Project requireMatchingReplay(Project existing, ProjectType type, String name) {
        if (!existing.matchesCreation(type, name)) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT",
                    "The request key was already used for a different project payload");
        }
        return existing;
    }

    private void publish(Project project, ProjectLifecycleEvent.Action action, Instant occurredAt) {
        events.publishEvent(new ProjectLifecycleEvent(project.getId(), project.getType(),
                project.getName(), action, occurredAt));
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Project operations require a tenant workspace");
        }
        return context;
    }
}
