package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTypedBindingWriter;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusRootKind;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectConversationFocusBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Persists the Project-to-focus FK link in the same transaction as ENTER or SWITCH. */
@Component
public final class ProjectConversationFocusBindingWriter
        implements ConversationFocusTypedBindingWriter {

    private final ProjectService projects;
    private final ProjectConversationFocusBindingRepository bindings;
    private final Clock clock;

    public ProjectConversationFocusBindingWriter(
            ProjectService projects,
            ProjectConversationFocusBindingRepository bindings,
            Clock clock) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String rootDomain() {
        return ProjectFocusContributor.DOMAIN;
    }

    @Override
    public void bind(ConversationFocus focus) {
        if (!rootDomain().equals(focus.getRootDomain())
                || focus.getRootKind() != ConversationFocusRootKind.WORKFLOW
                || focus.getWorkflowId() == null) {
            throw new IllegalStateException("Project focus requires a workflow identity");
        }
        projects.getProject(focus.getWorkflowId());
        ProjectConversationFocusBinding existing = bindings
                .findByConversationFocusIdAndWorkspaceIdAndCreatedByUserId(
                        focus.getId(), focus.getWorkspaceId(), focus.getCreatedByUserId())
                .orElse(null);
        if (existing != null) {
            if (!existing.getProjectId().equals(focus.getWorkflowId())) {
                throw new SecurityException("Project focus binding identity mismatch");
            }
            return;
        }
        bindings.save(ProjectConversationFocusBinding.create(
                focus.getId(), focus.getWorkflowId(), focus.getChannel(),
                focus.getConversationScopeDigest(), Instant.now(clock)));
    }
}
