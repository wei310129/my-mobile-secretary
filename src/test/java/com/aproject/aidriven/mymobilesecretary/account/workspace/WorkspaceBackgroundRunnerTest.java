package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUserStatus;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceMember;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.persistence.WorkspaceMemberRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceBackgroundRunnerTest {

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void runsOncePerWorkspaceUsingHighestActiveMutationRole() {
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        when(memberRepository.findAllForUsersWithStatus(AppUserStatus.ACTIVE)).thenReturn(List.of(
                WorkspaceMember.create(firstWorkspace, member, WorkspaceRole.MEMBER, owner, now),
                WorkspaceMember.create(firstWorkspace, owner, WorkspaceRole.OWNER, owner, now),
                WorkspaceMember.create(secondWorkspace, viewer, WorkspaceRole.VIEWER, viewer, now)));
        List<WorkspaceContext> observed = new ArrayList<>();

        WorkspaceBackgroundRunner.RunSummary result = new WorkspaceBackgroundRunner(memberRepository)
                .forEachWorkspace("test-job", context -> {
                    observed.add(WorkspaceContextHolder.requireContext());
                    assertThat(context.channel()).isEqualTo(WorkspaceChannel.BACKGROUND);
                });

        assertThat(observed).singleElement().satisfies(context -> {
            assertThat(context.workspaceId()).isEqualTo(firstWorkspace);
            assertThat(context.actorId()).isEqualTo(owner);
        });
        assertThat(result).isEqualTo(new WorkspaceBackgroundRunner.RunSummary(2, 1, 0, 1));
        assertThat(WorkspaceContextHolder.current()).isEmpty();
    }

    @Test
    void oneWorkspaceFailureDoesNotBlockTheNextWorkspace() {
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        when(memberRepository.findAllForUsersWithStatus(AppUserStatus.ACTIVE)).thenReturn(List.of(
                WorkspaceMember.create(firstWorkspace, firstActor, WorkspaceRole.OWNER, firstActor, now),
                WorkspaceMember.create(secondWorkspace, secondActor, WorkspaceRole.ADMIN, secondActor, now)));
        List<UUID> attempted = new ArrayList<>();

        WorkspaceBackgroundRunner.RunSummary result = new WorkspaceBackgroundRunner(memberRepository)
                .forEachWorkspace("test-job", context -> {
                    attempted.add(context.workspaceId());
                    if (context.workspaceId().equals(firstWorkspace)) {
                        throw new IllegalStateException("boom");
                    }
                });

        assertThat(attempted).containsExactly(firstWorkspace, secondWorkspace);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }
}
