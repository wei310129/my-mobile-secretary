package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** IntentIssue 資料存取。 */
public interface IntentIssueRepository extends JpaRepository<IntentIssue, Long> {

    List<IntentIssue> findByStatusOrderByCreatedAtDesc(IntentIssue.Status status);

    List<IntentIssue> findAllByOrderByCreatedAtDesc();

    List<IntentIssue> findAllByWorkspaceIdAndCreatedByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            UUID workspaceId,
            UUID createdByUserId,
            IntentIssue.Status status,
            long afterId,
            Pageable pageable);
}
