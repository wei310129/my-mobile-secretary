package com.aproject.aidriven.mymobilesecretary.project.application;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps Project scope validation, Project lock and child repository work in one transaction.
 */
@Service
public class ProjectScopedTransactionExecutor {

    private final ProjectScopePolicy policy;

    public ProjectScopedTransactionExecutor(ProjectScopePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Transactional
    public <T> T create(
            ProjectScope scope, Function<ProjectScopedCommand, T> repositoryWork) {
        return execute(policy.authorizeCreate(scope), repositoryWork);
    }

    @Transactional(readOnly = true)
    public <T> T query(
            ProjectScope scope, Function<ProjectScopedCommand, T> repositoryWork) {
        return execute(policy.authorizeQuery(scope), repositoryWork);
    }

    @Transactional
    public <T> T update(
            ProjectScope scope, UUID targetProjectId,
            Function<ProjectScopedCommand, T> repositoryWork) {
        return execute(
                policy.authorizeUpdate(scope, targetProjectId), repositoryWork);
    }

    @Transactional
    public <T> T delete(
            ProjectScope scope, UUID targetProjectId,
            Function<ProjectScopedCommand, T> repositoryWork) {
        return execute(
                policy.authorizeDelete(scope, targetProjectId), repositoryWork);
    }

    private static <T> T execute(
            ProjectScopedCommand command,
            Function<ProjectScopedCommand, T> repositoryWork) {
        return Objects.requireNonNull(repositoryWork, "repositoryWork")
                .apply(Objects.requireNonNull(command, "authorized command"));
    }
}
