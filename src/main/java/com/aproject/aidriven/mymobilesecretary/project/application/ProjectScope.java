package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authorization snapshot that can only be created after application-service
 * revalidation.
 */
public final class ProjectScope {

    private final UUID projectId;
    private final UUID workspaceId;
    private final UUID actorId;
    private final ProjectType type;
    private final long projectVersion;

    private ProjectScope(UUID projectId, UUID workspaceId, UUID actorId, ProjectType type,
                         long projectVersion) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.type = Objects.requireNonNull(type, "type");
        if (projectVersion < 0) {
            throw new IllegalArgumentException("projectVersion must not be negative");
        }
        this.projectVersion = projectVersion;
    }

    static ProjectScope validated(
            UUID projectId, UUID workspaceId, UUID actorId, ProjectType type,
            long projectVersion) {
        return new ProjectScope(projectId, workspaceId, actorId, type, projectVersion);
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID actorId() {
        return actorId;
    }

    public ProjectType type() {
        return type;
    }

    public long projectVersion() {
        return projectVersion;
    }
}
