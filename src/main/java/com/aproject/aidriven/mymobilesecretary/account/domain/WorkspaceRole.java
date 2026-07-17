package com.aproject.aidriven.mymobilesecretary.account.domain;

/** Roles are ordered by the permissions they grant within one workspace. */
public enum WorkspaceRole {
    VIEWER(10),
    MEMBER(20),
    ADMIN(30),
    OWNER(40);

    private final int authority;

    WorkspaceRole(int authority) {
        this.authority = authority;
    }

    public boolean grants(WorkspaceRole requiredRole) {
        return requiredRole != null && authority >= requiredRole.authority;
    }
}
