package com.aproject.aidriven.mymobilesecretary.account.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Trusted JWT/header names used to establish a REST workspace scope. */
@ConfigurationProperties("app.security.workspace-context")
public record RestWorkspaceProperties(
        @DefaultValue("app_user_id") String actorClaim,
        @DefaultValue("workspace_id") String workspaceClaim,
        @DefaultValue("X-Workspace-Id") String workspaceHeader
) {
}
