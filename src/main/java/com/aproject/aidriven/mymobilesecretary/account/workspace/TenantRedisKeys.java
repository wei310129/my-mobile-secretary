package com.aproject.aidriven.mymobilesecretary.account.workspace;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;

/** Central Redis key namespace: public caches may stay global; business state must use this. */
public final class TenantRedisKeys {

    private TenantRedisKeys() {
    }

    /** Preserves legacy keys while assigning every newer workspace a Redis Cluster hash tag. */
    public static String current(String legacyKey) {
        if (legacyKey == null || legacyKey.isBlank()) {
            throw new IllegalArgumentException("legacyKey is required");
        }
        var workspaceId = WorkspaceContextHolder.requireContext().workspaceId();
        return LegacyAccountIds.WORKSPACE_ID.equals(workspaceId)
                ? legacyKey
                : "ws:{" + workspaceId + "}:" + legacyKey;
    }
}
