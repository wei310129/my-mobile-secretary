package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Creates the trusted focus candidate only from an authorized Project entity. */
@Component
public final class ProjectConversationFocusBindingFactory {

    public ConversationFocusBinding from(Project project) {
        Objects.requireNonNull(project, "project");
        return ConversationFocusBinding.workflow(
                ProjectFocusContributor.DOMAIN, project.getId(), project.getName());
    }
}
