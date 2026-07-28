package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.util.Objects;
import java.util.UUID;

/**
 * Policy-authorized command passed to a child repository callback inside one transaction.
 */
public final class ProjectScopedCommand {

    private final ProjectScope scope;
    private final ProjectCrudOperation operation;

    private ProjectScopedCommand(ProjectScope scope, ProjectCrudOperation operation) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    static ProjectScopedCommand authorized(
            ProjectScope scope, ProjectCrudOperation operation) {
        return new ProjectScopedCommand(scope, operation);
    }

    public UUID projectId() {
        return scope.projectId();
    }

    public UUID workspaceId() {
        return scope.workspaceId();
    }

    public UUID actorId() {
        return scope.actorId();
    }

    public ProjectType projectType() {
        return scope.type();
    }

    public long projectVersion() {
        return scope.projectVersion();
    }

    public ProjectCrudOperation operation() {
        return operation;
    }
}
