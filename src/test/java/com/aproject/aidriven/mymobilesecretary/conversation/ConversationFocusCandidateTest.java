package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusControl;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusDecision;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusResponseEnvelope;
import com.aproject.aidriven.mymobilesecretary.conversation.application.PendingFocusTransitionService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusCandidateTest extends IntegrationTestBase {

    @Autowired private PendingFocusTransitionService pendingTransitions;
    @Autowired private ConversationFocusService focusService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void workflowCandidateCreatesNoFocusUntilItIsExplicitlyAccepted() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId)) {
            UUID candidateId = pendingTransitions.propose(
                    FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("TRAVEL", workflowId, "北海道旅行"),
                    "a".repeat(64)).getId();

            assertThat(count("conversation_focus", workspaceId)).isZero();
            assertThat(count("item", workspaceId)).isZero();
            assertThat(status(candidateId)).isEqualTo("PENDING");

            AtomicInteger domainMutations = new AtomicInteger();
            FocusResponseEnvelope reply = pendingTransitions.accept(candidateId, () -> {
                domainMutations.incrementAndGet();
                jdbcTemplate.update("""
                        INSERT INTO item (
                            name, created_at, inventory_quantity, shopping_needed, updated_at,
                            workspace_id, created_by_user_id)
                        VALUES ('candidate-created object', CURRENT_TIMESTAMP, 0, FALSE,
                            CURRENT_TIMESTAMP, ?, ?)
                        """, workspaceId, actorId);
                return FocusResponseEnvelope.withoutNotice("已建立旅遊專案");
            });

            assertThat(status(candidateId)).isEqualTo("ACCEPTED");
            assertThat(reply.message()).contains("已建立旅遊專案");
            assertThat(reply.notice().type()).isEqualTo(FocusTransitionType.ENTER);
            assertThat(jdbcTemplate.queryForObject("SELECT workflow_id FROM conversation_focus "
                    + "WHERE workspace_id = ? AND status = 'ACTIVE'", UUID.class, workspaceId))
                    .isEqualTo(workflowId);
            assertThat(count("item", workspaceId)).isEqualTo(1);
            assertThatThrownBy(() -> pendingTransitions.accept(candidateId, () -> {
                domainMutations.incrementAndGet();
                return FocusResponseEnvelope.withoutNotice("重播不可執行");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("pending focus transition is unavailable");
            assertThat(domainMutations).hasValue(1);
        }
    }

    @Test
    void changedFocusHeadExpiresCandidateBeforeItsDomainMutationRuns() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        AtomicBoolean domainMutationRan = new AtomicBoolean();

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId)) {
            UUID candidateId = pendingTransitions.propose(
                    FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("TRAVEL", UUID.randomUUID(), "沖繩旅行"),
                    "b".repeat(64)).getId();
            focusService.enterWorkflow("TRAVEL", UUID.randomUUID(), "目前旅行", "c".repeat(64));

            assertThatThrownBy(() -> pendingTransitions.accept(candidateId, () -> {
                domainMutationRan.set(true);
                return FocusResponseEnvelope.withoutNotice("不應建立");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("pending focus transition is expired");

            assertThat(domainMutationRan).isFalse();
            assertThat(status(candidateId)).isEqualTo("EXPIRED");
            assertThat(count("conversation_focus", workspaceId)).isEqualTo(1);
        }
    }

    @Test
    void aNewCandidateRejectsThePriorCandidateWithoutCreatingEitherWorkflowFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = open(actorId, workspaceId)) {
            UUID firstCandidate = pendingTransitions.propose(
                    FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("TRAVEL", UUID.randomUUID(), "第一趟旅行"),
                    "d".repeat(64)).getId();
            UUID secondCandidate = pendingTransitions.propose(
                    FocusDecision.transition(FocusTransitionType.ENTER),
                    new FocusControl.EnterWorkflow("TRAVEL", UUID.randomUUID(), "第二趟旅行"),
                    "e".repeat(64)).getId();

            assertThat(status(firstCandidate)).isEqualTo("REJECTED");
            assertThat(status(secondCandidate)).isEqualTo("PENDING");
            assertThat(count("conversation_focus", workspaceId)).isZero();
        }
    }

    private WorkspaceContextHolder.Scope open(UUID actorId, UUID workspaceId) {
        return WorkspaceContextHolder.open(new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST));
    }

    private long count(String table, UUID workspaceId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private String status(UUID candidateId) {
        return jdbcTemplate.queryForObject("SELECT status FROM pending_focus_transition WHERE id = ?",
                String.class, candidateId);
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Focus candidate user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Focus candidate workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }
}
