package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 品項比對規則測試:中文無斷詞,以「包含」判斷是否提到。
 */
class ItemTest {

    private static final Instant NOW = Instant.parse("2026-07-11T08:00:00Z");

    private final Item porkRibs = Item.create("排骨", Set.of(1L), NOW);

    @Test
    void mentionedWhenTitleContainsName() {
        assertThat(porkRibs.isMentionedIn("買排骨")).isTrue();
        assertThat(porkRibs.isMentionedIn("排骨半斤")).isTrue();
        assertThat(porkRibs.isMentionedIn("幫媽媽買排骨湯的料")).isTrue();
    }

    @Test
    void notMentionedWhenAbsent() {
        assertThat(porkRibs.isMentionedIn("買醬油")).isFalse();
        assertThat(porkRibs.isMentionedIn("")).isFalse();
        assertThat(porkRibs.isMentionedIn(null)).isFalse();
    }

    @Test
    void relativeInventoryNeverDropsBelowZero() {
        porkRibs.setInventoryQuantity(2, NOW);

        porkRibs.adjustInventoryQuantity(-1, NOW.plusSeconds(1));
        assertThat(porkRibs.getInventoryQuantity()).isEqualTo(1);

        porkRibs.adjustInventoryQuantity(-10, NOW.plusSeconds(2));
        assertThat(porkRibs.getInventoryQuantity()).isZero();

        porkRibs.adjustInventoryQuantity(3, NOW.plusSeconds(3));
        assertThat(porkRibs.getInventoryQuantity()).isEqualTo(3);
    }
}
