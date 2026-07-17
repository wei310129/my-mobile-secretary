package com.aproject.aidriven.mymobilesecretary.account.domain;

import java.util.UUID;

/** Stable compatibility identities used only while legacy single-user data is migrated. */
public final class LegacyAccountIds {

    public static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID MEMBERSHIP_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private LegacyAccountIds() {
    }
}
