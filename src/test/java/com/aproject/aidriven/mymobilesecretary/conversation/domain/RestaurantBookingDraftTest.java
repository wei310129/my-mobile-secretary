package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RestaurantBookingDraftTest {

    @Test
    void absorbsAllTypedValuesAndDoesNotAdvanceRevisionForTheSameAnswer() {
        Instant now = Instant.parse("2026-08-04T01:00:00Z");
        RestaurantBookingDraft draft = RestaurantBookingDraft.start(
                new ConversationScopeKey("d".repeat(64), 1), WorkspaceChannel.LINE,
                null, now.plusSeconds(300), now);
        Instant diningAt = now.plusSeconds(7200);

        draft.merge("測試餐廳", diningAt, 4, true, false, true, now.plusSeconds(1));
        long revision = draft.getRevision();
        draft.merge("測試餐廳", diningAt, 4, true, false, true, now.plusSeconds(2));

        assertThat(draft.isComplete()).isTrue();
        assertThat(draft.getRestaurantName()).isEqualTo("測試餐廳");
        assertThat(draft.getDiningAt()).isEqualTo(diningAt);
        assertThat(draft.getPartySize()).isEqualTo(4);
        assertThat(draft.isIncludesChild()).isTrue();
        assertThat(draft.isIncludesPet()).isTrue();
        assertThat(draft.getRevision()).isEqualTo(revision);
    }

    @Test
    void rejectsInvalidPartySizeAndCannotCompleteAnIncompleteDraft() {
        Instant now = Instant.parse("2026-08-04T01:00:00Z");
        RestaurantBookingDraft draft = RestaurantBookingDraft.start(
                new ConversationScopeKey("e".repeat(64), 1), WorkspaceChannel.LINE,
                null, now.plusSeconds(300), now);

        assertThatThrownBy(() -> draft.merge(
                null, null, 0, false, false, false, now.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.complete(now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }
}
