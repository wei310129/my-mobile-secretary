package com.aproject.aidriven.mymobilesecretary.account.application;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import com.aproject.aidriven.mymobilesecretary.account.domain.ExternalIdentity;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.ExternalIdentityRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an external platform subject into an internal actor and workspace.
 *
 * <p>An identity's default workspace is only a routing preference. Authorization is checked
 * against {@code workspace_member} on every resolution so removing a member takes effect on the
 * next request. Raw provider subjects must never be written to application logs or audit rows.</p>
 */
@Service
@Transactional(readOnly = true)
public class ExternalIdentityService {

    public static final String LINE_PROVIDER = "LINE";

    private final ExternalIdentityRepository identityRepository;
    private final AppUserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final Clock clock;

    public ExternalIdentityService(ExternalIdentityRepository identityRepository,
                                   AppUserRepository userRepository,
                                   WorkspaceMemberRepository memberRepository,
                                   WorkspaceAccessService workspaceAccessService,
                                   Clock clock) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.clock = clock;
    }

    public Resolution resolve(String provider, String subject) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedSubject = requireText(subject, "subject");
        return identityRepository.findByProviderAndSubject(normalizedProvider, normalizedSubject)
                .map(this::resolveLinkedIdentity)
                .orElseGet(() -> Resolution.failed(ResolutionStatus.NOT_LINKED));
    }

    /**
     * Keeps the configured single-owner installation working during the identity migration.
     * The compatibility result does not create an external identity for the legacy placeholder.
     */
    public Resolution resolveLine(String subject, String configuredOwnerSubject) {
        Resolution linked = resolve(LINE_PROVIDER, subject);
        if (linked.status() != ResolutionStatus.NOT_LINKED) {
            return linked;
        }
        if (!constantTimeEquals(subject, configuredOwnerSubject)) {
            return linked;
        }
        return Resolution.resolved(new ResolvedIdentity(
                LegacyAccountIds.USER_ID,
                LegacyAccountIds.WORKSPACE_ID,
                WorkspaceRole.OWNER,
                LINE_PROVIDER,
                true));
    }

    @Transactional
    public ExternalIdentity link(UUID userId, String provider, String subject,
                                 UUID defaultWorkspaceId) {
        AppUser user = userRepository.findById(Objects.requireNonNull(userId, "userId"))
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BusinessException(
                        "IDENTITY_USER_INACTIVE", "找不到可連結的有效使用者。"));
        workspaceAccessService.requireMembership(user.getId(),
                Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId"));

        String normalizedProvider = normalizeProvider(provider);
        String normalizedSubject = requireText(subject, "subject");
        if (identityRepository.findByProviderAndSubject(normalizedProvider, normalizedSubject).isPresent()
                || identityRepository.findByUserIdAndProvider(userId, normalizedProvider).isPresent()) {
            throw new BusinessException("IDENTITY_ALREADY_LINKED", "這個外部帳號已經完成連結。" );
        }

        try {
            return identityRepository.saveAndFlush(ExternalIdentity.create(userId, normalizedProvider,
                    normalizedSubject, defaultWorkspaceId, Instant.now(clock)));
        } catch (DataIntegrityViolationException conflict) {
            throw new BusinessException("IDENTITY_ALREADY_LINKED", "這個外部帳號已經完成連結。" );
        }
    }

    @Transactional
    public void selectDefaultWorkspace(UUID userId, String provider, UUID workspaceId) {
        workspaceAccessService.requireMembership(
                Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(workspaceId, "workspaceId"));
        ExternalIdentity identity = identityRepository.findByUserIdAndProvider(
                        userId, normalizeProvider(provider))
                .orElseThrow(() -> new BusinessException(
                        "IDENTITY_NOT_LINKED", "這個外部帳號尚未連結。"));
        identity.selectDefaultWorkspace(workspaceId, Instant.now(clock));
    }

    private Resolution resolveLinkedIdentity(ExternalIdentity identity) {
        AppUser user = userRepository.findById(identity.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            return Resolution.failed(ResolutionStatus.USER_INACTIVE);
        }

        UUID defaultWorkspaceId = identity.getDefaultWorkspaceId();
        if (defaultWorkspaceId != null) {
            return memberRepository.findByWorkspaceIdAndUserId(defaultWorkspaceId, user.getId())
                    .map(member -> resolved(identity, member))
                    .orElseGet(() -> Resolution.failed(ResolutionStatus.ACCESS_REVOKED));
        }

        List<WorkspaceMember> memberships = memberRepository.findAllByUserIdOrderByJoinedAtAsc(user.getId());
        if (memberships.isEmpty()) {
            return Resolution.failed(ResolutionStatus.NO_WORKSPACE);
        }
        if (memberships.size() > 1) {
            return Resolution.failed(ResolutionStatus.DEFAULT_WORKSPACE_REQUIRED);
        }
        return resolved(identity, memberships.getFirst());
    }

    private static Resolution resolved(ExternalIdentity identity, WorkspaceMember membership) {
        return Resolution.resolved(new ResolvedIdentity(
                identity.getUserId(), membership.getWorkspaceId(), membership.getRole(),
                identity.getProvider(), false));
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeProvider(String provider) {
        return requireText(provider, "provider").toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    public enum ResolutionStatus {
        RESOLVED,
        NOT_LINKED,
        USER_INACTIVE,
        NO_WORKSPACE,
        DEFAULT_WORKSPACE_REQUIRED,
        ACCESS_REVOKED
    }

    public record ResolvedIdentity(UUID actorUserId, UUID workspaceId, WorkspaceRole role,
                                   String provider, boolean legacyCompatibility) {
    }

    public record Resolution(ResolutionStatus status, ResolvedIdentity identity) {

        public static Resolution resolved(ResolvedIdentity identity) {
            return new Resolution(ResolutionStatus.RESOLVED, Objects.requireNonNull(identity, "identity"));
        }

        public static Resolution failed(ResolutionStatus status) {
            if (status == null || status == ResolutionStatus.RESOLVED) {
                throw new IllegalArgumentException("A failed resolution requires a failure status");
            }
            return new Resolution(status, null);
        }

        public boolean isResolved() {
            return status == ResolutionStatus.RESOLVED;
        }
    }
}
