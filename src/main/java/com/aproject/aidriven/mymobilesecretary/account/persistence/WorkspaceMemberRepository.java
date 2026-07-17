package com.aproject.aidriven.mymobilesecretary.account.persistence;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUserStatus;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    List<WorkspaceMember> findAllByUserIdOrderByJoinedAtAsc(UUID userId);

    @Query("""
            select member from WorkspaceMember member
            where member.userId in (
                select appUser.id from AppUser appUser where appUser.status = :status
            )
            order by member.workspaceId, member.joinedAt
            """)
    List<WorkspaceMember> findAllForUsersWithStatus(@Param("status") AppUserStatus status);
}
