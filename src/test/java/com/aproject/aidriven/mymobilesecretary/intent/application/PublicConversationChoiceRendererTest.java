package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicConversationChoiceRendererTest {

    @Test
    void rendersEveryMultiChoiceQuestionAsNumberedScanningBlocks() {
        var question = new PublicConversationChoiceQuestion(
                "sample.choice",
                "請選擇處理方式：",
                List.of(
                        new PublicConversationChoice("KEEP", "保留", "保留目前資料", Set.of("保留")),
                        new PublicConversationChoice("CANCEL", "取消", "不建立新資料", Set.of("取消"))));

        assertThat(PublicConversationChoiceRenderer.render(question))
                .isEqualTo("""
                        請選擇處理方式：

                        1. 保留：保留目前資料

                        2. 取消：不建立新資料""");
    }

    @Test
    void rejectsAChoiceQuestionWithFewerThanTwoOptions() {
        assertThatThrownBy(() -> new PublicConversationChoiceQuestion(
                        "sample.choice",
                        "請選擇處理方式：",
                        List.of(new PublicConversationChoice(
                                "ONLY", "唯一選項", "沒有選擇空間", Set.of("唯一選項")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesDisplayedChoiceNumbersIncludingFullWidthAndBoundedSelectionWords() {
        var question = new PublicConversationChoiceQuestion(
                "sample.choice",
                "請選擇處理方式：",
                List.of(
                        new PublicConversationChoice("KEEP", "保留", "保留目前資料", Set.of("保留")),
                        new PublicConversationChoice("CANCEL", "取消", "不建立新資料", Set.of("取消"))));

        assertThat(question.resolveAction("1")).contains("KEEP");
        assertThat(question.resolveAction("１")).contains("KEEP");
        assertThat(question.resolveAction("選2")).contains("CANCEL");
        assertThat(question.resolveAction("我選 ２")).contains("CANCEL");
        assertThat(question.resolveAction("3")).isEmpty();
    }
}
