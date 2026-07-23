package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusLazyInvalidationContributor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTargetResolver;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates Project workflow targets; the workflow UUID alone is never authorization. */
@Component
public final class ProjectFocusContributor
        implements ConversationFocusLazyInvalidationContributor {

    public static final String DOMAIN = "PROJECT";

    private final ProjectService projects;

    public ProjectFocusContributor(ProjectService projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public String rootDomain() {
        return DOMAIN;
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        if (target == null || !DOMAIN.equals(target.domain()) || !target.workflow()) {
            return false;
        }
        try {
            return projects.getProject(target.workflowId()).getStatus() != ProjectStatus.ARCHIVED;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
