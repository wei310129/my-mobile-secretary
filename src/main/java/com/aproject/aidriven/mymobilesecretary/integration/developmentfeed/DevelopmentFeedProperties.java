package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.integration.development-feed")
public record DevelopmentFeedProperties(
        boolean enabled,
        String bearerToken,
        UUID workspaceId,
        UUID actorId,
        int maxPageSize,
        int maxPayloadBytes
) {
    public DevelopmentFeedProperties {
        bearerToken = bearerToken == null ? "" : bearerToken.strip();
        if (maxPageSize <= 0 || maxPageSize > 5000) {
            throw new IllegalArgumentException("development feed maxPageSize must be between 1 and 5000");
        }
        if (maxPayloadBytes < 4096 || maxPayloadBytes > 1048576) {
            throw new IllegalArgumentException(
                    "development feed maxPayloadBytes must be between 4096 and 1048576");
        }
        if (enabled) {
            if (bearerToken.length() < 32) {
                throw new IllegalArgumentException(
                        "development feed bearerToken must contain at least 32 characters");
            }
            if (workspaceId == null || actorId == null
                    || WorkspaceContext.NIL_ID.equals(workspaceId)
                    || WorkspaceContext.NIL_ID.equals(actorId)) {
                throw new IllegalArgumentException(
                        "development feed requires non-NIL workspaceId and actorId");
            }
        }
    }
}
