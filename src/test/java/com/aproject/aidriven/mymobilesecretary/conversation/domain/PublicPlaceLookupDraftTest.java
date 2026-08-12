package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.geo.domain.SystemPlaceCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicPlaceLookupDraftTest {

    private static final Instant NOW = Instant.parse("2026-08-03T06:00:00Z");

    @Test
    void storesOnlySortedCatalogKeysAndTypedMetadata() {
        PublicPlaceLookupDraft draft = PublicPlaceLookupDraft.create(
                new ConversationScopeKey("a".repeat(64), 1),
                WorkspaceChannel.LINE,
                PublicPlaceLookupDraftMode.READ_ONLY,
                SystemPlaceCategory.METRO_STATION,
                List.of("TDX:METRO:2", "TDX:METRO:1", "TDX:METRO:2"),
                null,
                NOW.plusSeconds(3600),
                NOW);

        assertThat(draft.candidateKeyList())
                .containsExactly("TDX:METRO:1", "TDX:METRO:2");
        assertThat(draft.getStatus()).isEqualTo(PublicPlaceLookupDraftStatus.PENDING);
        assertThat(draft.getRevision()).isEqualTo(1);
    }

    @Test
    void rejectsArbitraryTextAndSelectionOutsideCandidates() {
        assertThatThrownBy(() -> PublicPlaceLookupDraft.create(
                new ConversationScopeKey("a".repeat(64), 1),
                WorkspaceChannel.LINE,
                PublicPlaceLookupDraftMode.READ_ONLY,
                null,
                List.of("not a catalog key"),
                null,
                NOW.plusSeconds(3600),
                NOW)).isInstanceOf(IllegalArgumentException.class);

        PublicPlaceLookupDraft draft = PublicPlaceLookupDraft.create(
                new ConversationScopeKey("a".repeat(64), 1),
                WorkspaceChannel.LINE,
                PublicPlaceLookupDraftMode.READ_ONLY,
                null,
                List.of("TDX:METRO:1"),
                null,
                NOW.plusSeconds(3600),
                NOW);
        assertThatThrownBy(() -> draft.complete("TDX:METRO:2", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
