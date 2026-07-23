package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fail-closed authorization policy for Project-scoped child CRUD. */
@Component
public class ProjectScopePolicy {

    private final ProjectService projects;

    public ProjectScopePolicy(ProjectService projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public ProjectScopedCommand authorizeCreate(ProjectScope scope) {
        return authorize(scope, ProjectCrudOperation.CREATE, null, true);
    }

    public ProjectScopedCommand authorizeQuery(ProjectScope scope) {
        return authorize(scope, ProjectCrudOperation.QUERY, null, false);
    }

    public ProjectScopedCommand authorizeUpdate(
            ProjectScope scope, UUID targetProjectId) {
        return authorize(scope, ProjectCrudOperation.UPDATE, targetProjectId, true);
    }

    public ProjectScopedCommand authorizeDelete(
            ProjectScope scope, UUID targetProjectId) {
        return authorize(scope, ProjectCrudOperation.DELETE, targetProjectId, true);
    }

    private ProjectScopedCommand authorize(
            ProjectScope scope, ProjectCrudOperation operation, UUID targetProjectId,
            boolean lockForMutation) {
        Objects.requireNonNull(scope, "Project scope is required");
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()
                || !context.workspaceId().equals(scope.workspaceId())
                || !context.actorId().equals(scope.actorId())) {
            throw new SecurityException(
                    "Project scope does not belong to the current actor and workspace");
        }
        if (targetProjectId != null && !scope.projectId().equals(targetProjectId)) {
            throw new SecurityException("target belongs to a different Project");
        }
        Project project = lockForMutation
                ? projects.lockProjectForScope(scope.projectId())
                : projects.getProject(scope.projectId());
        if (!project.getId().equals(scope.projectId())
                || project.getType() != scope.type()) {
            throw new SecurityException("Project scope identity does not match");
        }
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("archived Project cannot authorize child CRUD");
        }
        if (!Objects.equals(project.getVersion(), scope.projectVersion())) {
            throw new IllegalStateException(
                    "Project scope is stale and must be resolved again");
        }
        return ProjectScopedCommand.authorized(scope, operation);
    }
}
