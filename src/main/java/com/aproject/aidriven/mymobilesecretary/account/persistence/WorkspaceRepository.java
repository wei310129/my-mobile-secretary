package com.aproject.aidriven.mymobilesecretary.account.persistence;

import com.aproject.aidriven.mymobilesecretary.account.domain.Workspace;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByTypeAndCreatedByUserId(WorkspaceType type, UUID createdByUserId);
}
