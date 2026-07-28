package com.aproject.aidriven.mymobilesecretary.project.persistence;

import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectConversationFocusBinding;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectConversationFocusBindingRepository
        extends JpaRepository<ProjectConversationFocusBinding, UUID> {

    Optional<ProjectConversationFocusBinding>
            findByConversationFocusIdAndWorkspaceIdAndCreatedByUserId(
                    UUID conversationFocusId, UUID workspaceId, UUID actorId);
}
