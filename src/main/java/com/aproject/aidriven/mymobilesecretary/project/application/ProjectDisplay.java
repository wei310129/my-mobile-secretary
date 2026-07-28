package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;

/** Audience-safe projection deliberately omitting persistence identifiers. */
public record ProjectDisplay(String name, ProjectType type, ProjectStatus status) {
    public static ProjectDisplay from(Project project) {
        return new ProjectDisplay(project.getName(), project.getType(), project.getStatus());
    }
}
