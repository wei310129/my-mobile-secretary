package com.aproject.aidriven.mymobilesecretary.api.intent;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import java.time.Instant;

/** 意圖問題的 API 回應。 */
public record IntentIssueResponse(
        Long id,
        String utterance,
        String botReply,
        IntentIssue.Category category,
        IntentIssue.Status status,
        Instant createdAt
) {

    public static IntentIssueResponse from(IntentIssue issue) {
        return new IntentIssueResponse(issue.getId(), issue.getUtterance(), issue.getBotReply(),
                issue.getCategory(), issue.getStatus(), issue.getCreatedAt());
    }
}
