package com.aproject.aidriven.mymobilesecretary.family.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Key;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Visibility;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FamilyPersonAttributeTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void allowlistedOrdinaryAttributeCanBeMarkedForFutureFamilySharing() {
        FamilyPersonAttribute attribute = FamilyPersonAttribute.create(
                1L, Key.SCHOOL, "測試幼兒園", Visibility.FAMILY, NOW);

        assertThat(attribute.getVisibility()).isEqualTo(Visibility.FAMILY);
    }

    @Test
    void medicalAndFreeFormAttributesRemainPrivate() {
        assertThatThrownBy(() -> FamilyPersonAttribute.create(
                1L, Key.BLOOD_TYPE, "O", Visibility.FAMILY, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be shared");
        assertThatThrownBy(() -> FamilyPersonAttribute.create(
                1L, Key.NOTE, "私人看法", Visibility.FAMILY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
