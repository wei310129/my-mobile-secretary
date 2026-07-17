package com.aproject.aidriven.mymobilesecretary.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import com.aproject.aidriven.mymobilesecretary.account.domain.ExternalIdentity;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.AppUserRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.ExternalIdentityRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock
    private ExternalIdentityRepository identityRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private WorkspaceMemberRepository memberRepository;
    @Mock
    private WorkspaceAccessService workspaceAccessService;

    private ExternalIdentityService service;

    @BeforeEach
    void setUp() {
        service = new ExternalIdentityService(identityRepository, userRepository, memberRepository,
                workspaceAccessService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void resolvesLinkedIdentityOnlyWhenDefaultWorkspaceMembershipStillExists() {
        AppUser user = AppUser.create("Aiden", NOW);
        UUID workspaceId = UUID.randomUUID();
        ExternalIdentity identity = ExternalIdentity.create(
                user.getId(), "LINE", "line-subject", workspaceId, NOW);
        WorkspaceMember membership = WorkspaceMember.create(
                workspaceId, user.getId(), WorkspaceRole.MEMBER, user.getId(), NOW);
        when(identityRepository.findByProviderAndSubject("LINE", "line-subject"))
                .thenReturn(Optional.of(identity));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()))
                .thenReturn(Optional.of(membership));

        ExternalIdentityService.Resolution result = service.resolve("line", "line-subject");

        assertThat(result.isResolved()).isTrue();
        assertThat(result.identity().actorUserId()).isEqualTo(user.getId());
        assertThat(result.identity().workspaceId()).isEqualTo(workspaceId);
        assertThat(result.identity().role()).isEqualTo(WorkspaceRole.MEMBER);
        verify(memberRepository).findByWorkspaceIdAndUserId(workspaceId, user.getId());
    }

    @Test
    void revokedDefaultWorkspaceFailsClosed() {
        AppUser user = AppUser.create("Aiden", NOW);
        UUID workspaceId = UUID.randomUUID();
        ExternalIdentity identity = ExternalIdentity.create(
                user.getId(), "LINE", "line-subject", workspaceId, NOW);
        when(identityRepository.findByProviderAndSubject("LINE", "line-subject"))
                .thenReturn(Optional.of(identity));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()))
                .thenReturn(Optional.empty());

        assertThat(service.resolve("LINE", "line-subject").status())
                .isEqualTo(ExternalIdentityService.ResolutionStatus.ACCESS_REVOKED);
    }

    @Test
    void identityWithoutDefaultCanUseItsOnlyWorkspace() {
        AppUser user = AppUser.create("Aiden", NOW);
        ExternalIdentity identity = ExternalIdentity.create(user.getId(), "LINE", "line-subject", NOW);
        WorkspaceMember membership = WorkspaceMember.create(
                UUID.randomUUID(), user.getId(), WorkspaceRole.VIEWER, user.getId(), NOW);
        when(identityRepository.findByProviderAndSubject("LINE", "line-subject"))
                .thenReturn(Optional.of(identity));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(memberRepository.findAllByUserIdOrderByJoinedAtAsc(user.getId()))
                .thenReturn(List.of(membership));

        assertThat(service.resolve("LINE", "line-subject").identity().workspaceId())
                .isEqualTo(membership.getWorkspaceId());
    }

    @Test
    void multipleWorkspacesRequireExplicitDefault() {
        AppUser user = AppUser.create("Aiden", NOW);
        ExternalIdentity identity = ExternalIdentity.create(user.getId(), "LINE", "line-subject", NOW);
        when(identityRepository.findByProviderAndSubject("LINE", "line-subject"))
                .thenReturn(Optional.of(identity));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(memberRepository.findAllByUserIdOrderByJoinedAtAsc(user.getId())).thenReturn(List.of(
                WorkspaceMember.create(UUID.randomUUID(), user.getId(), WorkspaceRole.OWNER, user.getId(), NOW),
                WorkspaceMember.create(UUID.randomUUID(), user.getId(), WorkspaceRole.MEMBER, user.getId(), NOW)));

        assertThat(service.resolve("LINE", "line-subject").status())
                .isEqualTo(ExternalIdentityService.ResolutionStatus.DEFAULT_WORKSPACE_REQUIRED);
    }

    @Test
    void configuredOwnerUsesLegacyCompatibilityWithoutPersistingRawSubject() {
        when(identityRepository.findByProviderAndSubject("LINE", "configured-owner"))
                .thenReturn(Optional.empty());

        ExternalIdentityService.Resolution result = service.resolveLine(
                "configured-owner", "configured-owner");

        assertThat(result.isResolved()).isTrue();
        assertThat(result.identity().actorUserId()).isEqualTo(LegacyAccountIds.USER_ID);
        assertThat(result.identity().workspaceId()).isEqualTo(LegacyAccountIds.WORKSPACE_ID);
        assertThat(result.identity().legacyCompatibility()).isTrue();
        verify(identityRepository, never()).save(any());
    }

    @Test
    void unlinkedStrangerIsNotMappedToLegacyOwner() {
        when(identityRepository.findByProviderAndSubject("LINE", "stranger"))
                .thenReturn(Optional.empty());

        assertThat(service.resolveLine("stranger", "configured-owner").status())
                .isEqualTo(ExternalIdentityService.ResolutionStatus.NOT_LINKED);
    }

    @Test
    void linkingRequiresWorkspaceMembershipBeforePersistingIdentity() {
        AppUser user = AppUser.create("Aiden", NOW);
        UUID workspaceId = UUID.randomUUID();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceAccessService.requireMembership(user.getId(), workspaceId))
                .thenThrow(new BusinessException("WORKSPACE_ACCESS_DENIED", "denied"));

        assertThatThrownBy(() -> service.link(
                user.getId(), "LINE", "line-subject", workspaceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WORKSPACE_ACCESS_DENIED"));
        verify(identityRepository, never()).save(any());
    }
}
