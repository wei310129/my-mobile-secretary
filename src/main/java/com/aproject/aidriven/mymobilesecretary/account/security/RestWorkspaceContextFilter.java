package com.aproject.aidriven.mymobilesecretary.account.security;

import com.aproject.aidriven.mymobilesecretary.account.application.WorkspaceAccessService;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditDraft;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditEvent;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes the actor/workspace boundary before any REST controller or persistence call. */
public final class RestWorkspaceContextFilter extends OncePerRequestFilter {

    private final boolean securityEnabled;
    private final RestWorkspaceProperties properties;
    private final AppUserRepository userRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final SecurityAuditService securityAuditService;

    public RestWorkspaceContextFilter(boolean securityEnabled,
                                      RestWorkspaceProperties properties,
                                      AppUserRepository userRepository,
                                      WorkspaceAccessService workspaceAccessService,
                                      SecurityAuditService securityAuditService) {
        this.securityEnabled = securityEnabled;
        this.properties = properties;
        this.userRepository = userRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.securityAuditService = securityAuditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/line/webhook".equals(path)
                || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!securityEnabled) {
            runScoped(new WorkspaceContext(
                    LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.LOCAL),
                    request, response, filterChain);
            return;
        }

        UUID actorId = authenticatedActorId();
        if (actorId == null) {
            deny(response, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                    "REST_ACTOR_REQUIRED", "無法確認登入使用者。" );
            return;
        }
        UUID workspaceId = requestedWorkspaceId(request);
        if (workspaceId == null) {
            deny(response, actorId, null, HttpServletResponse.SC_BAD_REQUEST,
                    "REST_WORKSPACE_REQUIRED", "請指定有效的工作區。" );
            return;
        }

        // Hibernate asks for a tenant id when opening any session, including account lookups.
        // NIL is not a real workspace, so accidental business queries during authentication see
        // no rows and cannot persist a valid foreign key.
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                WorkspaceContext.authentication())) {
            Optional<AppUser> actor = userRepository.findById(actorId).filter(AppUser::isActive);
            if (actor.isEmpty()) {
                deny(response, actorId, workspaceId, HttpServletResponse.SC_FORBIDDEN,
                        "REST_ACTOR_INACTIVE", "這個使用者目前無法使用服務。" );
                return;
            }
            try {
                WorkspaceMember membership = workspaceAccessService.requireMembership(actorId, workspaceId);
                if (!isReadOnly(request) && !membership.grants(WorkspaceRole.MEMBER)) {
                    deny(response, actorId, workspaceId, HttpServletResponse.SC_FORBIDDEN,
                            "WORKSPACE_ROLE_REQUIRED", "這個工作區角色只能檢視資料。" );
                    return;
                }
            } catch (BusinessException denied) {
                deny(response, actorId, workspaceId, HttpServletResponse.SC_FORBIDDEN,
                        denied.getCode(), "你沒有這個工作區的存取權。" );
                return;
            }
        }
        runScoped(new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.REST),
                request, response, filterChain);
    }

    private UUID authenticatedActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            return null;
        }
        Object claim = jwt.getToken().getClaims().get(properties.actorClaim());
        return parseUuid(claim);
    }

    private UUID requestedWorkspaceId(HttpServletRequest request) {
        String header = request.getHeader(properties.workspaceHeader());
        if (header != null && !header.isBlank()) {
            return parseUuid(header);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return parseUuid(jwt.getToken().getClaims().get(properties.workspaceClaim()));
        }
        return null;
    }

    private void runScoped(WorkspaceContext context,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           FilterChain filterChain) throws IOException, ServletException {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            filterChain.doFilter(request, response);
        }
    }

    private void deny(HttpServletResponse response, UUID actorId, UUID workspaceId,
                      int status, String reasonCode, String message) throws IOException {
        securityAuditService.recordSafely(new SecurityAuditDraft(
                workspaceId, actorId, "REST_WORKSPACE_RESOLUTION", "WORKSPACE",
                workspaceId == null ? null : workspaceId.toString(),
                SecurityAuditEvent.Outcome.DENIED, reasonCode, "REST",
                RequestCorrelationContext.currentId()));
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + reasonCode
                + "\",\"message\":\"" + message + "\"}");
    }

    private static UUID parseUuid(Object candidate) {
        if (candidate instanceof UUID uuid) {
            return uuid;
        }
        if (!(candidate instanceof String text) || text.length() > 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isReadOnly(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }
}
