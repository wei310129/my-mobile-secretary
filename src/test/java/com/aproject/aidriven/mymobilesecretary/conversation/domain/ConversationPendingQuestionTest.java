package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationPendingQuestionTest {

    @Test
    void sameInboundIsIdempotentAndNewInboundAdvancesExactlyOneRevision() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar", UUID.randomUUID(), "calendar.start", "b".repeat(64),
                first.plusSeconds(60), first);

        assertThat(pending.ask("calendar.duration", "b".repeat(64), first.plusSeconds(1)))
                .isFalse();
        assertThat(pending.getQuestionCode()).isEqualTo("calendar.start");
        assertThat(pending.getRevision()).isEqualTo(1);

        assertThat(pending.ask("calendar.duration", "c".repeat(64), first.plusSeconds(2)))
                .isTrue();
        assertThat(pending.getQuestionCode()).isEqualTo("calendar.duration");
        assertThat(pending.getRevision()).isEqualTo(2);
    }

    @Test
    void expiryUsesSuppliedClockInstantAndFailsClosed() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar", UUID.randomUUID(), "calendar.start", "b".repeat(64),
                first.plusSeconds(60), first);

        assertThat(pending.expireIfDue(first.plusSeconds(59))).isFalse();
        assertThat(pending.expireIfDue(first.plusSeconds(60))).isTrue();
        assertThat(pending.getStatus()).isEqualTo(ConversationPendingQuestionStatus.EXPIRED);
    }

    @Test
    void cancelIsTerminalAndDoesNotAdvanceRevision() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar", UUID.randomUUID(), "calendar.start", "b".repeat(64),
                first.plusSeconds(60), first);

        pending.cancel(first.plusSeconds(1));

        assertThat(pending.getStatus()).isEqualTo(ConversationPendingQuestionStatus.CANCELED);
        assertThat(pending.getRevision()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pending.ask(
                "calendar.duration", "c".repeat(64), first.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lifecycleCancelRecordsInboundAndSameInboundReplayIsNoOp() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar", UUID.randomUUID(), "calendar.start", "b".repeat(64),
                first.plusSeconds(60), first);

        assertThat(pending.cancel("c".repeat(64), first.plusSeconds(1))).isTrue();
        assertThat(pending.cancel("c".repeat(64), first.plusSeconds(2))).isFalse();

        assertThat(pending.getStatus()).isEqualTo(ConversationPendingQuestionStatus.CANCELED);
        assertThat(pending.getInboundIdempotencyHmac()).isEqualTo("c".repeat(64));
        assertThat(pending.getRevision()).isEqualTo(2);
    }

    @Test
    void contextChoiceRetainsTypedQuestionAndSafeLabelThenRestoresExactlyOnce() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar_draft", UUID.randomUUID(), "route.general-buffer",
                "大坪林到台北車站", "b".repeat(64), first.plusSeconds(60), first);

        assertThat(pending.beginContextChoice("c".repeat(64), first.plusSeconds(1))).isTrue();
        assertThat(pending.getQuestionCode()).isEqualTo("conversation.context-target");
        assertThat(pending.getInterruptedQuestionCode()).isEqualTo("route.general-buffer");
        assertThat(pending.getWorkflowSafeLabel()).isEqualTo("大坪林到台北車站");

        assertThat(pending.resumeInterruptedQuestion("d".repeat(64), first.plusSeconds(2)))
                .isTrue();
        assertThat(pending.getQuestionCode()).isEqualTo("route.general-buffer");
        assertThat(pending.getInterruptedQuestionCode()).isNull();
        assertThat(pending.getRevision()).isEqualTo(3);
    }

    @Test
    void newOperationContentCompletesPointerWithoutStoringInboundText() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar_draft", UUID.randomUUID(), "route.general-buffer",
                "大坪林到台北車站", "b".repeat(64), first.plusSeconds(60), first);

        pending.beginContextChoice("c".repeat(64), first.plusSeconds(1));
        pending.requestNewOperationContent("d".repeat(64), first.plusSeconds(2));
        assertThat(pending.completeNewOperationContent("e".repeat(64), first.plusSeconds(3)))
                .isTrue();

        assertThat(pending.getStatus()).isEqualTo(ConversationPendingQuestionStatus.ANSWERED);
        assertThat(pending.getQuestionCode()).isEqualTo("conversation.new-operation-content");
        assertThat(pending.getInboundIdempotencyHmac()).isEqualTo("e".repeat(64));
    }

    @Test
    void genericQuestionRewriteCannotDiscardInterruptedContext() {
        Instant first = Instant.parse("2026-08-02T01:00:00Z");
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(
                new ConversationScopeKey("a".repeat(64), 1), WorkspaceChannel.LINE,
                null, "calendar_draft", UUID.randomUUID(), "route.general-buffer",
                "大坪林到台北車站", "b".repeat(64), first.plusSeconds(60), first);
        pending.beginContextChoice("c".repeat(64), first.plusSeconds(1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pending.ask(
                        "weather.location", "d".repeat(64), first.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resumed or completed explicitly");
        assertThat(pending.getQuestionCode()).isEqualTo("conversation.context-target");
        assertThat(pending.getInterruptedQuestionCode()).isEqualTo("route.general-buffer");
    }
}
