package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObjectAnnotationTest {

    @Test
    void storesGenericTargetAndFreeFormDetail() {
        ObjectAnnotation annotation = ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "青葉水泥漆", "客廳工程第二批有色差", Instant.EPOCH);

        assertThat(annotation.getTargetId()).isEqualTo(8L);
        assertThat(annotation.getSubject()).isEqualTo("青葉水泥漆");
        assertThat(annotation.getDetail()).isEqualTo("客廳工程第二批有色差");
        assertThat(annotation.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(annotation.getUpdatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void rejectsMissingTargetOrEmptyAnnotation() {
        assertThatThrownBy(() -> ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, null,
                "青葉水泥漆", "註記", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "青葉水泥漆", " ", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editingKeepsCreatedAtAndAdvancesUpdatedAt() {
        Instant created = Instant.parse("2026-07-20T00:00:00Z");
        Instant updated = created.plusSeconds(60);
        ObjectAnnotation annotation = ObjectAnnotation.create(
                ObjectAnnotation.TargetType.PRODUCT_OBSERVATION, 8L,
                "青葉水泥漆", "舊內容", created);

        annotation.update("客廳油漆", "大麥白 107", updated);

        assertThat(annotation.getCreatedAt()).isEqualTo(created);
        assertThat(annotation.getUpdatedAt()).isEqualTo(updated);
        assertThat(annotation.getSubject()).isEqualTo("客廳油漆");
        assertThat(annotation.getDetail()).isEqualTo("大麥白 107");
    }
}
