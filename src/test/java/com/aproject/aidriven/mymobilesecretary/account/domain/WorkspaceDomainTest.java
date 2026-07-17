package com.aproject.aidriven.mymobilesecretary.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceDomainTest {

    @Test
    void personalAndHouseholdFactoriesKeepCreatorAndType() {
        UUID creator = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-16T12:00:00Z");

        Workspace personal = Workspace.createPersonal(creator, "My space", now);
        Workspace household = Workspace.createHousehold(creator, "Family", now);

        assertThat(personal.getType()).isEqualTo(WorkspaceType.PERSONAL);
        assertThat(household.getType()).isEqualTo(WorkspaceType.HOUSEHOLD);
        assertThat(personal.getCreatedByUserId()).isEqualTo(creator);
        assertThat(household.getCreatedByUserId()).isEqualTo(creator);
    }

    @Test
    void ownerAndAdminGrantOnlyTheirRoleLevelOrLower() {
        assertThat(WorkspaceRole.OWNER.grants(WorkspaceRole.OWNER)).isTrue();
        assertThat(WorkspaceRole.ADMIN.grants(WorkspaceRole.MEMBER)).isTrue();
        assertThat(WorkspaceRole.ADMIN.grants(WorkspaceRole.OWNER)).isFalse();
        assertThat(WorkspaceRole.VIEWER.grants(WorkspaceRole.MEMBER)).isFalse();
    }
}
