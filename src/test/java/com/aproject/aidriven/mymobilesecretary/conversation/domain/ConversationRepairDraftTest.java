package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationRepairDraftTest {

    @Test
    void draftContainsOnlyTypedRepairStateAndUsesInjectedTime() {
        Instant now = Instant.parse("2026-08-03T01:00:00Z");
        ConversationRepairDraft draft = ConversationRepairDraft.create(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                ConversationRepairKind.WRONG_ANSWER,
                ConversationRepairPriorAction.TASKS_LISTED,
                ConversationRepairScope.TODAY, now.plusSeconds(300), now);

        assertThat(draft.getRepairKind()).isEqualTo(ConversationRepairKind.WRONG_ANSWER);
        assertThat(draft.getPriorAction()).isEqualTo(ConversationRepairPriorAction.TASKS_LISTED);
        assertThat(draft.getTimeScope()).isEqualTo(ConversationRepairScope.TODAY);
        assertThat(draft.getRepairAspect()).isEqualTo(ConversationRepairAspect.UNSPECIFIED);
        assertThat(draft.getStatus()).isEqualTo(ConversationRepairDraftStatus.PENDING);
        assertThat(draft.getRevision()).isEqualTo(1);

        draft.complete(now.plusSeconds(1));

        assertThat(draft.getStatus()).isEqualTo(ConversationRepairDraftStatus.COMPLETED);
        assertThat(draft.getRevision()).isEqualTo(2);
        assertThatThrownBy(() -> draft.cancel(now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refinementStoresOnlyATypedAspectAndIsIdempotent() {
        Instant now = Instant.parse("2026-08-03T01:00:00Z");
        ConversationRepairDraft draft = ConversationRepairDraft.create(
                new ConversationScopeKey("c".repeat(64), 1), WorkspaceChannel.LINE,
                ConversationRepairKind.DISSATISFACTION,
                ConversationRepairPriorAction.OTHER,
                ConversationRepairScope.UPCOMING, now.plusSeconds(300), now);

        draft.refine(ConversationRepairAspect.PRESENTATION, now.plusSeconds(1));
        long revision = draft.getRevision();
        draft.refine(ConversationRepairAspect.PRESENTATION, now.plusSeconds(2));

        assertThat(draft.getRepairAspect()).isEqualTo(ConversationRepairAspect.PRESENTATION);
        assertThat(draft.getRevision()).isEqualTo(revision);
    }

    @Test
    void expiryIsTerminalAtTheExactBoundary() {
        Instant now = Instant.parse("2026-08-03T01:00:00Z");
        ConversationRepairDraft draft = ConversationRepairDraft.create(
                new ConversationScopeKey("b".repeat(64), 2), WorkspaceChannel.LINE,
                ConversationRepairKind.REPEATED_QUESTION,
                ConversationRepairPriorAction.OTHER,
                ConversationRepairScope.UPCOMING, now.plusSeconds(60), now);

        assertThat(draft.expireIfDue(now.plusSeconds(59))).isFalse();
        assertThat(draft.expireIfDue(now.plusSeconds(60))).isTrue();
        assertThat(draft.getStatus()).isEqualTo(ConversationRepairDraftStatus.EXPIRED);
    }
}
