package com.aproject.aidriven.mymobilesecretary.project.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.persistence.ProjectConversationFocusBindingRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns an active Project focus into a scope only after typed-binding and domain revalidation. */
@Service
@Transactional(readOnly = true)
public class ProjectScopeFromFocusService {

    private final ConversationFocusService focuses;
    private final ProjectConversationFocusBindingRepository bindings;
    private final ProjectService projects;

    public ProjectScopeFromFocusService(
            ConversationFocusService focuses,
            ProjectConversationFocusBindingRepository bindings,
            ProjectService projects) {
        this.focuses = Objects.requireNonNull(focuses, "focuses");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public Optional<ProjectScope> active() {
        return focuses.activeFocus()
                .filter(focus -> ProjectFocusContributor.DOMAIN.equals(focus.getRootDomain()))
                .map(this::revalidate);
    }

    public ProjectScope requireActive() {
        return active().orElseThrow(() -> new IllegalStateException(
                "no active Project focus is available"));
    }

    private ProjectScope revalidate(ConversationFocus focus) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (focus.getWorkflowId() == null) {
            throw new SecurityException("Project focus has no workflow identity");
        }
        ProjectConversationFocusBinding binding = bindings
                .findByConversationFocusIdAndWorkspaceIdAndCreatedByUserId(
                        focus.getId(), context.workspaceId(), context.actorId())
                .orElseThrow(() -> new SecurityException(
                        "typed Project focus binding is required"));
        if (!binding.getProjectId().equals(focus.getWorkflowId())) {
            throw new SecurityException("typed Project focus binding does not match workflow");
        }
        Project project = projects.getProject(binding.getProjectId());
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("archived Project cannot authorize a scope");
        }
        return ProjectScope.validated(
                project.getId(), context.workspaceId(), context.actorId(), project.getType(),
                project.getVersion());
    }
}
