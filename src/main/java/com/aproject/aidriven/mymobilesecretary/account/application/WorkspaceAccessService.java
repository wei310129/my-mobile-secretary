package com.aproject.aidriven.mymobilesecretary.account.application;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.domain.Workspace;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;

    public WorkspaceAccessService(WorkspaceRepository workspaceRepository,
                                  WorkspaceMemberRepository memberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
    }

    /** Returns the compatibility workspace that owns all records created before V18. */
    public Workspace getLegacyWorkspace() {
        return workspaceRepository.findById(LegacyAccountIds.WORKSPACE_ID)
                .orElseThrow(() -> new IllegalStateException("Legacy workspace is missing"));
    }

    public boolean isMember(UUID userId, UUID workspaceId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    /** Fails closed when the actor has no membership in the requested workspace. */
    public WorkspaceMember requireMembership(UUID userId, UUID workspaceId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(
                        "WORKSPACE_ACCESS_DENIED", "你沒有此工作空間的存取權限。"));
    }

    /** Requires a minimum role using OWNER > ADMIN > MEMBER > VIEWER ordering. */
    public WorkspaceMember requireRole(UUID userId, UUID workspaceId, WorkspaceRole requiredRole) {
        Objects.requireNonNull(requiredRole, "requiredRole");
        WorkspaceMember membership = requireMembership(userId, workspaceId);
        if (!membership.grants(requiredRole)) {
            throw new BusinessException("WORKSPACE_ROLE_REQUIRED",
                    "此操作需要 %s 或更高的工作空間權限。".formatted(requiredRole));
        }
        return membership;
    }
}
