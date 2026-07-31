package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntentScriptCompletenessPolicyTest {

    @Test
    void preservesRecognizedIntentAndSurfacesLaterInstructionThatWasOmitted() {
        IntentScript raw = new IntentScript(List.of(command(
                IntentCommand.Type.CREATE_SCHEDULE, "產品會議", "明天上午十點產品會議")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "明天上午十點產品會議，另外提醒我帶女兒英文課教材", raw);

        assertThat(complete.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN);
        assertThat(complete.commands().getLast().reason()).contains("提醒我帶女兒英文課教材");
        assertThat(complete.commands().getLast().sourceText()).isEqualTo("另外提醒我帶女兒英文課教材");
    }

    @Test
    void keepsEveryGroundedCommandInOriginalOrder() {
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.CREATE_SCHEDULE, "產品會議", "明天上午十點產品會議"),
                command(IntentCommand.Type.CREATE_TASK, "帶英文課教材", "另外提醒我帶女兒英文課教材")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "明天上午十點產品會議，另外提醒我帶女兒英文課教材", raw);

        assertThat(complete.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.CREATE_TASK);
    }

    @Test
    void doesNotSplitACompoundOperationWhenInterpreterProvidesNoSourceEvidence() {
        IntentCommand feasibility = new IntentCommand(
                IntentCommand.Type.CHECK_FEASIBILITY, "假設客戶會議", null,
                "2026-07-19T09:00:00+08:00", "2026-07-19T10:00:00+08:00",
                null, null, null, null, null, null, null, null, IntentOptions.empty());

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "明天九點開會一小時，先不要建，幫我看前面準備加後面交通會不會撞到",
                new IntentScript(List.of(feasibility)));

        assertThat(complete.commands()).containsExactly(feasibility);
    }

    @Test
    void distinguishesSingleChildClassInstructionFromSeparateFollowUp() {
        assertThat(IntentScriptCompletenessPolicy.hasIndependentActionableClause(
                "每週六早上十點到十二點送女兒上英文課")).isFalse();
        assertThat(IntentScriptCompletenessPolicy.hasIndependentActionableClause(
                "每週六早上十點送女兒上英文課，另外提醒我帶教材")).isTrue();
    }

    @Test
    void childCourseMessageAlwaysSelectsShortTermContextForSpeechCorrection() {
        assertThat(IntentPromptContextBuilder.Selection.forMessage(
                "女兒明天要上英國課，幫我確認一下").shortTermContext()).isTrue();
    }

    @Test
    void surfacesUnsupportedAdjacentGapFacetWithoutDroppingSupportedAnalytics() {
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "哪一天最忙"),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "最長的一筆是哪一筆")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "告訴我下週所有行程裡哪一天最忙、最長的一筆是哪一筆，"
                        + "並列出那筆行程前後還各剩多少空檔",
                raw);

        assertThat(complete.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                IntentCommand.Type.UNKNOWN);
        assertThat(complete.commands().getLast().reason())
                .contains("指定行程前後", "目前還不能", "不會建立或修改資料")
                .doesNotContain("Intent", "typed", "handler");
    }

    @Test
    void recognizesParaphrasedAdjacentGapFacetByMeaningNotOneExactSentence() {
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "排得最滿的日子"),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "耗時最久的活動")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "請找出這週排得最滿的日子和耗時最久的活動，"
                        + "還要說明該活動之前與之後各空多久",
                raw);

        assertThat(complete.commands()).extracting(IntentCommand::type).containsExactly(
                IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                IntentCommand.Type.UNKNOWN);
    }

    @Test
    void supportedBusyAndLongestCompoundDoesNotGainFalseClarification() {
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "哪一天行程最多"),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "時間最長的行程")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "請告訴我下週哪一天行程最多，以及時間最長的行程是哪一筆", raw);

        assertThat(complete.commands()).containsExactlyElementsOf(raw.commands());
    }

    @Test
    void readOnlyAnalysisRejectsAnInventedScheduleMutation() {
        IntentScript raw = new IntentScript(List.of(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "專案會議", "查專案會議前後還有多少空檔")));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "幫我查專案會議前後還有多少空檔", raw);

        assertThat(complete.commands()).singleElement().satisfies(command -> {
            assertThat(command.type()).isEqualTo(IntentCommand.Type.UNKNOWN);
            assertThat(command.reason()).contains("不會建立或修改資料");
        });
    }

    @Test
    void existingAdjacentGapClarificationIsNotDuplicated() {
        IntentCommand gapClarification = new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "需要先知道最長那筆行程，才能查詢其前後的空檔時間",
                null, null, null, null, null, null,
                "那筆行程前後各剩多少空檔");
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "哪一天最忙"),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "最長的一筆"),
                gapClarification));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                "告訴我下週哪一天最忙、最長的一筆是哪一筆，"
                        + "並列出那筆行程前後還各剩多少空檔", raw);

        assertThat(complete.commands()).containsExactlyElementsOf(raw.commands());
    }

    @Test
    void genericUnknownWithWholeSourceDoesNotPretendToExplainTheMissingFacet() {
        IntentCommand genericUnknown = new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "有一個操作無法對應到你這次的原話",
                null, null, null, null, null, null,
                "告訴我下週哪一天最忙、最長的一筆是哪一筆，"
                        + "並列出那筆行程前後還各剩多少空檔");
        IntentScript raw = new IntentScript(List.of(
                command(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
                        null, "哪一天最忙"),
                command(IntentCommand.Type.ASK_LONGEST_SCHEDULE,
                        null, "最長的一筆"),
                genericUnknown));

        IntentScript complete = IntentScriptCompletenessPolicy.apply(
                genericUnknown.sourceText(), raw);

        assertThat(complete.commands()).hasSize(4);
        assertThat(complete.commands().getLast().reason())
                .contains("指定行程前後的相鄰空檔");
    }

    private static IntentCommand command(IntentCommand.Type type, String title, String sourceText) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null, null, sourceText);
    }
}
