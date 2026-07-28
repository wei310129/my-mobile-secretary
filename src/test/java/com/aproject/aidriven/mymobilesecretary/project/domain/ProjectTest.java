package com.aproject.aidriven.mymobilesecretary.project.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProjectTest {

    private static final Instant CREATED_AT = Instant.parse("2030-04-01T00:00:00Z");

    @Test
    void projectStartsActiveWithoutDraftStateAndNormalizesItsName() {
        Project project = Project.create(ProjectType.TRAVEL, "  大阪家庭旅行  ",
                "a".repeat(64), CREATED_AT);

        assertThat(project.getId()).isNotNull();
        assertThat(project.getType()).isEqualTo(ProjectType.TRAVEL);
        assertThat(project.getName()).isEqualTo("大阪家庭旅行");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(ProjectStatus.values()).extracting(Enum::name).doesNotContain("DRAFT");
        assertThat(project.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(project.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void lifecycleAllowsCompleteReopenAndArchiveOnlyThroughValidTransitions() {
        Project project = project();
        Instant completedAt = CREATED_AT.plusSeconds(60);
        Instant reopenedAt = CREATED_AT.plusSeconds(120);
        Instant archivedAt = CREATED_AT.plusSeconds(180);

        project.complete(completedAt);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        assertThat(project.getCompletedAt()).isEqualTo(completedAt);

        project.reopen(reopenedAt);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getCompletedAt()).isNull();

        project.archive(archivedAt);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(project.getArchivedAt()).isEqualTo(archivedAt);

        assertThatThrownBy(() -> project.rename("不能修改", archivedAt.plusSeconds(1)))
                .hasMessageContaining("archived");
        assertThatThrownBy(() -> project.reopen(archivedAt.plusSeconds(1)))
                .hasMessageContaining("ARCHIVED");
        assertThatThrownBy(() -> project.archive(archivedAt.plusSeconds(1)))
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    void repeatedOrOutOfOrderTransitionsFailClosed() {
        Project active = project();
        assertThatThrownBy(() -> active.reopen(CREATED_AT.plusSeconds(1)))
                .hasMessageContaining("ACTIVE");

        active.complete(CREATED_AT.plusSeconds(2));
        assertThatThrownBy(() -> active.complete(CREATED_AT.plusSeconds(3)))
                .hasMessageContaining("COMPLETED");

        active.rename("大阪與京都", CREATED_AT.plusSeconds(4));
        assertThat(active.getName()).isEqualTo("大阪與京都");
        active.archive(CREATED_AT.plusSeconds(5));
        assertThatThrownBy(() -> active.complete(CREATED_AT.plusSeconds(6)))
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    void creationRequiresBoundedNameAndNonReversibleHmac() {
        assertThatThrownBy(() -> Project.create(ProjectType.TRAVEL, " ", "a".repeat(64), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Project.create(ProjectType.TRAVEL, "旅行",
                "raw-message-id", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Project.create(ProjectType.TRAVEL, "x".repeat(201),
                "b".repeat(64), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Project project() {
        return Project.create(ProjectType.TRAVEL, "大阪旅行", "c".repeat(64), CREATED_AT);
    }
}
