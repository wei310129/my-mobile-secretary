package com.aproject.aidriven.mymobilesecretary.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.application.WorkspaceAccessService;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditDraft;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RestWorkspaceContextFilterTest {

    private static final RestWorkspaceProperties PROPERTIES = new RestWorkspaceProperties(
            "app_user_id", "workspace_id", "X-Workspace-Id");

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private WorkspaceAccessService workspaceAccessService;
    @Mock
    private SecurityAuditService securityAuditService;

    @AfterEach
    void cleanThreadState() {
        SecurityContextHolder.clearContext();
        WorkspaceContextHolder.clear();
    }

    @Test
    void localCompatibilityOpensAndClosesLegacyScope() throws Exception {
        RestWorkspaceContextFilter filter = filter(false);
        AtomicReference<com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext>
                observed = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/tasks"),
                new MockHttpServletResponse(),
                (request, response) -> observed.set(WorkspaceContextHolder.requireContext()));

        assertThat(observed.get().actorId()).isEqualTo(LegacyAccountIds.USER_ID);
        assertThat(observed.get().workspaceId()).isEqualTo(LegacyAccountIds.WORKSPACE_ID);
        assertThat(observed.get().channel()).isEqualTo(WorkspaceChannel.LOCAL);
        assertThat(WorkspaceContextHolder.current()).isEmpty();
        verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatedRequestRevalidatesActorAndMembershipThenOpensScope() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser actor = AppUser.create("Aiden", Instant.parse("2026-07-16T12:00:00Z"));
        authenticate(Map.of("app_user_id", actorId.toString()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(workspaceAccessService.requireMembership(actorId, workspaceId)).thenReturn(
                WorkspaceMember.create(workspaceId, actorId, WorkspaceRole.MEMBER,
                        actorId, Instant.parse("2026-07-16T12:00:00Z")));
        AtomicReference<com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext>
                observed = new AtomicReference<>();

        filter(true).doFilter(request, new MockHttpServletResponse(),
                (servletRequest, response) -> observed.set(WorkspaceContextHolder.requireContext()));

        assertThat(observed.get().actorId()).isEqualTo(actorId);
        assertThat(observed.get().workspaceId()).isEqualTo(workspaceId);
        assertThat(observed.get().channel()).isEqualTo(WorkspaceChannel.REST);
        verify(workspaceAccessService).requireMembership(actorId, workspaceId);
    }

    @Test
    void missingWorkspaceFailsBeforeControllerAndWritesSafeAudit() throws Exception {
        UUID actorId = UUID.randomUUID();
        authenticate(Map.of("app_user_id", actorId.toString()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();

        filter(true).doFilter(new MockHttpServletRequest("POST", "/api/tasks"), response,
                (request, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("REST_WORKSPACE_REQUIRED");
        verify(securityAuditService).recordSafely(
                org.mockito.ArgumentMatchers.any(SecurityAuditDraft.class));
    }

    @Test
    void revokedMembershipReturnsForbiddenAndNeverEntersScope() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser actor = AppUser.create("Aiden", Instant.parse("2026-07-16T12:00:00Z"));
        authenticate(Map.of("app_user_id", actorId.toString()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(workspaceAccessService.requireMembership(actorId, workspaceId))
                .thenThrow(new BusinessException("WORKSPACE_ACCESS_DENIED", "denied"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("controller must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        ArgumentCaptor<SecurityAuditDraft> audit = ArgumentCaptor.forClass(SecurityAuditDraft.class);
        verify(securityAuditService).recordSafely(audit.capture());
        assertThat(audit.getValue().reasonCode()).isEqualTo("WORKSPACE_ACCESS_DENIED");
        assertThat(audit.getValue().workspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void viewerCanReadButCannotCallMutationEndpoint() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser actor = AppUser.create("Viewer", Instant.parse("2026-07-16T12:00:00Z"));
        authenticate(Map.of("app_user_id", actorId.toString()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(workspaceAccessService.requireMembership(actorId, workspaceId)).thenReturn(
                WorkspaceMember.create(workspaceId, actorId, WorkspaceRole.VIEWER,
                        actorId, Instant.parse("2026-07-16T12:00:00Z")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("viewer mutation must not reach controller");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        ArgumentCaptor<SecurityAuditDraft> audit = ArgumentCaptor.forClass(SecurityAuditDraft.class);
        verify(securityAuditService).recordSafely(audit.capture());
        assertThat(audit.getValue().reasonCode()).isEqualTo("WORKSPACE_ROLE_REQUIRED");
    }

    private RestWorkspaceContextFilter filter(boolean enabled) {
        return new RestWorkspaceContextFilter(enabled, PROPERTIES, userRepository,
                workspaceAccessService, securityAuditService);
    }

    private static void authenticate(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token").header("alg", "none");
        claims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(), Collections.emptyList()));
    }
}
