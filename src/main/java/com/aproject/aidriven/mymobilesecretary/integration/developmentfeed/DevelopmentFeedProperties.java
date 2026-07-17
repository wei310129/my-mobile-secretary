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
        int maxPageSize
) {
    public DevelopmentFeedProperties {
        bearerToken = bearerToken == null ? "" : bearerToken.strip();
        if (maxPageSize <= 0 || maxPageSize > 5000) {
            throw new IllegalArgumentException("development feed maxPageSize must be between 1 and 5000");
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
