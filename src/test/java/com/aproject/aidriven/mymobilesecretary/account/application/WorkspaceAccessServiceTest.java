package com.aproject.aidriven.mymobilesecretary.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.Workspace;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMemberRepository memberRepository;

    private WorkspaceAccessService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceAccessService(workspaceRepository, memberRepository);
    }

    @Test
    void obtainsLegacyWorkspaceByStableId() {
        Workspace workspace = Workspace.createPersonal(LegacyAccountIds.USER_ID, "Personal", NOW);
        when(workspaceRepository.findById(LegacyAccountIds.WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        assertThat(service.getLegacyWorkspace()).isSameAs(workspace);
        verify(workspaceRepository).findById(LegacyAccountIds.WORKSPACE_ID);
    }

    @Test
    void membershipLookupUsesBothWorkspaceAndActor() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WorkspaceMember membership = WorkspaceMember.create(
                workspaceId, userId, WorkspaceRole.MEMBER, userId, NOW);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.of(membership));

        assertThat(service.requireMembership(userId, workspaceId)).isSameAs(membership);
        verify(memberRepository).findByWorkspaceIdAndUserId(workspaceId, userId);
    }

    @Test
    void missingMembershipFailsClosed() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireMembership(userId, workspaceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void roleRequirementUsesExplicitRoleHierarchy() {
        UUID workspaceId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        WorkspaceMember admin = WorkspaceMember.create(
                workspaceId, adminId, WorkspaceRole.ADMIN, adminId, NOW);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, adminId))
                .thenReturn(Optional.of(admin));

        assertThat(service.requireRole(adminId, workspaceId, WorkspaceRole.MEMBER)).isSameAs(admin);
        assertThatThrownBy(() -> service.requireRole(adminId, workspaceId, WorkspaceRole.OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WORKSPACE_ROLE_REQUIRED"));
    }
}
