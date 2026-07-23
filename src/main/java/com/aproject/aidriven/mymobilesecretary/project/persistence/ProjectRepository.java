package com.aproject.aidriven.mymobilesecretary.project.persistence;

import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByWorkspaceIdAndCreatedByUserIdAndCreationRequestHmac(
            UUID workspaceId, UUID actorId, String creationRequestHmac);

    Optional<Project> findByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Project> findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
            UUID id, UUID workspaceId, UUID actorId);

    List<Project> findAllByWorkspaceIdAndCreatedByUserIdOrderByUpdatedAtDesc(
            UUID workspaceId, UUID actorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO project (
                id, project_type, name, status, creation_request_hmac, version,
                created_at, updated_at, workspace_id, created_by_user_id)
            VALUES (
                :id, :projectType, :name, 'ACTIVE', :creationRequestHmac, 0,
                :now, :now, :workspaceId, :actorId)
            ON CONFLICT (
                workspace_id, created_by_user_id, creation_request_hmac)
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("projectType") String projectType,
                       @Param("name") String name,
                       @Param("creationRequestHmac") String creationRequestHmac,
                       @Param("now") Instant now,
                       @Param("workspaceId") UUID workspaceId,
                       @Param("actorId") UUID actorId);
}
