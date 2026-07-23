package com.aproject.aidriven.mymobilesecretary.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ProjectScopedTransactionExecutorTest {

    @Test
    void validationFailureNeverInvokesChildRepositoryCallback() {
        ProjectScopePolicy policy = mock(ProjectScopePolicy.class);
        ProjectScope scope = ProjectScope.validated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProjectType.TRAVEL, 1L);
        when(policy.authorizeUpdate(scope, scope.projectId()))
                .thenThrow(new SecurityException("denied before child repository"));
        ProjectScopedTransactionExecutor executor =
                new ProjectScopedTransactionExecutor(policy);
        AtomicBoolean repositoryCalled = new AtomicBoolean();

        assertThatThrownBy(() -> executor.update(
                scope, scope.projectId(), command -> {
                    repositoryCalled.set(true);
                    return "unexpected";
                })).isInstanceOf(SecurityException.class);

        assertThat(repositoryCalled).isFalse();
    }

    @Test
    void successfulCallbackReceivesOnlyPolicyAuthorizedCommand() {
        ProjectScopePolicy policy = mock(ProjectScopePolicy.class);
        UUID projectId = UUID.randomUUID();
        ProjectScope scope = ProjectScope.validated(
                projectId, UUID.randomUUID(), UUID.randomUUID(),
                ProjectType.TRAVEL, 2L);
        ProjectScopedCommand authorized = ProjectScopedCommand.authorized(
                scope, ProjectCrudOperation.CREATE);
        when(policy.authorizeCreate(scope)).thenReturn(authorized);
        ProjectScopedTransactionExecutor executor =
                new ProjectScopedTransactionExecutor(policy);

        UUID selected = executor.create(scope, ProjectScopedCommand::projectId);

        assertThat(selected).isEqualTo(projectId);
    }

    @Test
    void everyCrudEntryPointOwnsAnExplicitSpringTransaction() throws Exception {
        assertTransactional("create", ProjectScope.class, Function.class, false);
        assertTransactional("query", ProjectScope.class, Function.class, true);
        assertTransactional(
                "update", ProjectScope.class, UUID.class, Function.class, false);
        assertTransactional(
                "delete", ProjectScope.class, UUID.class, Function.class, false);
    }

    private static void assertTransactional(
            String name, Class<?> first, Class<?> callback, boolean readOnly)
            throws Exception {
        assertTransactional(name, first, null, callback, readOnly);
    }

    private static void assertTransactional(
            String name, Class<?> first, Class<?> second, Class<?> callback,
            boolean readOnly) throws Exception {
        Method method = second == null
                ? ProjectScopedTransactionExecutor.class.getMethod(name, first, callback)
                : ProjectScopedTransactionExecutor.class.getMethod(
                        name, first, second, callback);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isEqualTo(readOnly);
    }
}
