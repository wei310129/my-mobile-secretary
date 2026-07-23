package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.time.Instant;
import java.util.UUID;

public record ProjectLifecycleEvent(UUID projectId, ProjectType projectType, String name,
                                    Action action, Instant occurredAt) {
    public enum Action {
        CREATED,
        RENAMED,
        COMPLETED,
        REOPENED,
        ARCHIVED
    }
}
