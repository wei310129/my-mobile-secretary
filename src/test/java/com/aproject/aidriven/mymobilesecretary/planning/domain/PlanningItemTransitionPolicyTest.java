package com.aproject.aidriven.mymobilesecretary.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlanningItemTransitionPolicyTest {

    private static final Instant START = Instant.parse("2026-07-20T02:00:00Z");

    @Test
    void persistedItemNeverReturnsToDraft() {
        var decision = PlanningItemTransitionPolicy.assess(
                PlanningItemType.TODO, PlanningItemType.DRAFT,
                shape("拿包裹", null, null, false, false, false));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.requirements()).containsExactly("已離開草稿的項目不可轉回草稿");
    }

    @Test
    void todoMustNotKeepExecutionTime() {
        var decision = PlanningItemTransitionPolicy.assess(
                PlanningItemType.SCHEDULE_REMINDER, PlanningItemType.TODO,
                shape("拿包裹", START, null, false, false, false));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.requirements()).anyMatch(value -> value.contains("移除時間"));
    }

    @Test
    void scheduleReminderNeedsOnePointAndNeverAnOccupiedRange() {
        assertThat(PlanningItemTransitionPolicy.assess(
                PlanningItemType.DRAFT, PlanningItemType.SCHEDULE_REMINDER,
                shape("繳費", START, null, false, false, false)).allowed()).isTrue();

        var range = PlanningItemTransitionPolicy.assess(
                PlanningItemType.TODO, PlanningItemType.SCHEDULE_REMINDER,
                shape("繳費", START, START.plusSeconds(3600), false, false, false));
        assertThat(range.allowed()).isFalse();
        assertThat(range.requirements()).contains("行程提醒是單一時點，不可帶占用時段");
    }

    @Test
    void scheduleRequiresPresenceRangeConflictAndFeasibilityChecks() {
        var missing = PlanningItemTransitionPolicy.assess(
                PlanningItemType.DRAFT, PlanningItemType.SCHEDULE,
                shape("看醫生", START, START.plusSeconds(3600), true, false, false));

        assertThat(missing.allowed()).isFalse();
        assertThat(missing.requirements()).containsExactly("撞期檢查", "地點與可行性檢查");

        assertThat(PlanningItemTransitionPolicy.assess(
                PlanningItemType.TODO, PlanningItemType.SCHEDULE,
                shape("看醫生", START, START.plusSeconds(3600), true, true, true))
                .allowed()).isTrue();
    }

    @Test
    void knowledgeOnlyComesFromDraftAndHasNoCompletionState() {
        assertThat(PlanningItemTransitionPolicy.assess(
                PlanningItemType.DRAFT, PlanningItemType.KNOWLEDGE,
                shape("油漆色號大麥白 107", null, null, false, false, false)).allowed()).isTrue();
        assertThat(PlanningItemType.KNOWLEDGE.completable()).isFalse();

        assertThat(PlanningItemTransitionPolicy.assess(
                PlanningItemType.TODO, PlanningItemType.KNOWLEDGE,
                shape("拿包裹", null, null, false, false, false)).requirements())
                .containsExactly("知識紀錄只能由草稿轉入");
        assertThat(PlanningItemTransitionPolicy.assess(
                PlanningItemType.KNOWLEDGE, PlanningItemType.TODO,
                shape("油漆色號", null, null, false, false, false)).requirements())
                .containsExactly("知識紀錄是長期保存資料，不轉成待辦、行程提醒或行程");
    }

    private static PlanningItemShape shape(String title, Instant start, Instant end,
                                           boolean presence, boolean conflicts,
                                           boolean feasibility) {
        return new PlanningItemShape(title, start, end, presence, conflicts, feasibility);
    }
}
