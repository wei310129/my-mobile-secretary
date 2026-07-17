package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReminderScheduleWorkspaceKeyTest {

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void preservesLegacyQueueAndSeparatesEveryNewWorkspace() {
        String legacy;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.TEST))) {
            legacy = ReminderScheduleService.queueKey();
        }
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstKey;
        String secondKey;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(UUID.randomUUID(), first, WorkspaceChannel.TEST))) {
            firstKey = ReminderScheduleService.queueKey();
        }
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(UUID.randomUUID(), second, WorkspaceChannel.TEST))) {
            secondKey = ReminderScheduleService.queueKey();
        }

        assertThat(legacy).isEqualTo("reminder:schedule");
        assertThat(firstKey).isEqualTo("reminder:{" + first + "}:schedule");
        assertThat(secondKey).isEqualTo("reminder:{" + second + "}:schedule").isNotEqualTo(firstKey);
    }
}
