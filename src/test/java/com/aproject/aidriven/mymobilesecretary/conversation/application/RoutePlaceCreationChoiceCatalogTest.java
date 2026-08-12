package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceRenderer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoutePlaceCreationChoiceCatalogTest {

    @ParameterizedTest
    @MethodSource("answers")
    void resolvesPublicLabelsAndCommonAnswersFromTheSameCatalog(
            String answer, String actionCode) {
        assertThat(RoutePlaceCreationChoiceCatalog.usePlace().resolveAction(answer))
                .contains(actionCode);
    }

    @org.junit.jupiter.api.Test
    void exposesStorageCategoryAndEffectAsNumberedOptions() {
        assertThat(PublicConversationChoiceRenderer.render(
                        RoutePlaceCreationChoiceCatalog.usePlace()))
                .contains(
                        "1. 儲存為個人地點紀錄：之後可再次使用",
                        "2. 作為本次一次性地點：只用於這次路線，不保存到個人地點",
                        "3. 取消建立地點：保留原本路線規劃，返回選擇出發地")
                .doesNotContain("要「儲存並繼續」", "還是「取消建立地點」");
    }

    private static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of("儲存並繼續", RoutePlaceCreationChoiceCatalog.SAVE),
                Arguments.of("儲存地點", RoutePlaceCreationChoiceCatalog.SAVE),
                Arguments.of("保存這個地點", RoutePlaceCreationChoiceCatalog.SAVE),
                Arguments.of("把這個地點存起來", RoutePlaceCreationChoiceCatalog.SAVE),
                Arguments.of("儲存為個人地點紀錄", RoutePlaceCreationChoiceCatalog.SAVE),
                Arguments.of("只用這次", RoutePlaceCreationChoiceCatalog.ONE_TIME),
                Arguments.of("作為本次一次性地點", RoutePlaceCreationChoiceCatalog.ONE_TIME),
                Arguments.of("取消建立地點", RoutePlaceCreationChoiceCatalog.CANCEL));
    }
}
