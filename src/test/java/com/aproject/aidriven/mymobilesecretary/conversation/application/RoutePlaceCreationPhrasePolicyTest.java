package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoutePlaceCreationPhrasePolicyTest {

    @ParameterizedTest
    @CsvSource({
        "從公司出發,公司",
        "由公司出發,公司",
        "公司出發,公司",
        "自公司啟程,公司",
        "由公司起程,公司",
        "從公司動身,公司",
        "從公司開始走,公司",
        "從公司走過去,公司",
        "我要從公司出發,公司",
        "我想從公司出發,公司",
        "打算由公司啟程,公司",
        "就從公司這邊出發,公司",
        "從公司那裡出發,公司",
        "從「公司」出發,公司",
        "由 公司 啟程,公司",
        "公司作為出發地,公司",
        "公司當作出發地點,公司",
        "公司設為起點,公司",
        "以公司為出發地,公司",
        "公司為起點,公司",
        "出發地是公司,公司",
        "出發地點：公司,公司",
        "起點為公司,公司",
        "我的出發地是公司,公司",
        "公司,公司",
        "「重新出發」,重新出發"
    })
    void normalizesCommonOriginWordingWithoutHardCodingWholeSentences(
            String phrase, String expectedAlias) {
        assertThat(RouteOriginInputPolicy.resolve(phrase, phrase))
                .hasValueSatisfying(input -> {
                    assertThat(input.kind())
                            .isEqualTo(RouteOriginInputPolicy.Kind.NAMED_PLACE);
                    assertThat(input.alias()).isEqualTo(expectedAlias);
                    assertThat(input.query()).isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "從我目前的位置出發",
        "從我現在的位置出發",
        "由我的位置出發",
        "自我目前所在地啟程",
        "以目前位置為起點",
        "就從這裡出發",
        "從這邊走",
        "由此處動身",
        "從這個位置出發",
        "我人在這裡，從這裡開始走"
    })
    void recognizesCurrentLocationAsTransientInsteadOfASavedPlace(String phrase) {
        assertThat(RouteOriginInputPolicy.resolve(phrase, phrase))
                .hasValueSatisfying(input -> {
                    assertThat(input.kind())
                            .isEqualTo(RouteOriginInputPolicy.Kind.TRANSIENT_CURRENT_LOCATION);
                    assertThat(input.alias()).isEqualTo("目前位置");
                    assertThat(input.query()).isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "從這裡 https://maps.app.goo.gl/abc123 出發",
        "目前位置（https://maps.app.goo.gl/abc123）",
        "以這個位置為起點：https://maps.app.goo.gl/abc123"
    })
    void extractsMapsEvidenceForATransientCurrentLocation(String phrase) {
        assertThat(RouteOriginInputPolicy.resolve(phrase, phrase))
                .hasValueSatisfying(input -> {
                    assertThat(input.kind())
                            .isEqualTo(RouteOriginInputPolicy.Kind.TRANSIENT_CURRENT_LOCATION);
                    assertThat(input.query()).isEqualTo("https://maps.app.goo.gl/abc123");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "重新出發咖啡",
        "出發工作室",
        "公司出發口",
        "起點咖啡",
        "動身旅行社"
    })
    void preservesPlaceNamesThatOnlyContainTransportWords(String placeName) {
        assertThat(RouteOriginInputPolicy.resolve(placeName, placeName))
                .hasValueSatisfying(input -> assertThat(input.alias()).isEqualTo(placeName));
    }

    @ParameterizedTest
    @CsvSource({
        "公司地點在內湖富邦大樓,公司,內湖富邦大樓",
        "好，公司就是內湖富邦大樓,公司,內湖富邦大樓",
        "工作地點位於台北101,工作,台北101",
        "辦公室就在南港軟體園區,辦公室,南港軟體園區"
    })
    void extractsTypedAliasAndQueryAcrossCommonWording(
            String text, String alias, String query) {
        assertThat(RoutePlaceCreationPhrasePolicy.parse(text))
                .hasValueSatisfying(result -> {
                    assertThat(result.alias()).isEqualTo(alias);
                    assertThat(result.query()).isEqualTo(query);
                });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "幫我建立今天中午十二點跟同事聚餐的行程",
        "現在處理到哪裡了？",
        "這不是我要處理的內容"
    })
    void rejectsInstructionsThatDoNotBelongToPlaceDetails(String text) {
        assertThat(RoutePlaceCreationPhrasePolicy.clearlyIncompatibleWithPlaceDetails(text))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "公司在哪裡？",
        "幫我規劃從公司到高鐵桃園站"
    })
    void rejectsReadOnlyAndRouteNeighborPhrases(String text) {
        assertThat(RoutePlaceCreationPhrasePolicy.parse(text)).isEmpty();
    }

    @Test
    void rejectsADeclarationWithoutAUsableLocationQuery() {
        assertThat(RoutePlaceCreationPhrasePolicy.parse("公司地點在公司")).isEmpty();
    }
}
