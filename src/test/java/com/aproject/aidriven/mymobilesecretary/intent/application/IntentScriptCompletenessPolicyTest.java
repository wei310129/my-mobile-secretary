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

    private static IntentCommand command(IntentCommand.Type type, String title, String sourceText) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null, null, sourceText);
    }
}
