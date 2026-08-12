package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SystemPlaceConversationServiceTest {

    private SystemPlaceConversationService service;
    private PlaceAliasService aliases;

    @BeforeEach
    void setUp() {
        aliases = mock(PlaceAliasService.class);
        when(aliases.resolve(anyString())).thenReturn(Optional.empty());
        service = new SystemPlaceConversationService(
                new SystemPlaceCatalog(new ClassPathResource("system-place-catalog.tsv")),
                aliases);
    }

    @Test
    void categoryOnlyTurnAnswersWithoutPrematureClarification() {
        IntentResult result = service.answer("捷運站").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message()).contains("202", "捷運站", "不用先選");
        assertThat(result.message()).doesNotContain("？");
    }

    @Test
    void correctionNamesTheSystemPlaceMismatchAndRepairsItInTheSameTurn() {
        IntentResult result = service.answer(
                "我是說系統應該有自己的地點，包含捷運站、港口、機場、高鐵、火車站、縣市政府與遊樂園")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message())
                .contains("系統自有公共地點", "不是要建立使用者自訂地點", "捷運站", "機場");
    }

    @Test
    void compositionalCorrectionDoesNotBecomeAnAmbiguousStationLookup() {
        IntentResult result = service.answer(
                "我是說系統要有自己的捷運站、港口、機場、高鐵、火車站、縣市政府和遊樂園地點")
                .orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message())
                .contains("系統自有公共地點", "不是要建立使用者自訂地點")
                .doesNotContain("哪個縣市");
    }

    @Test
    void exactSystemPlaceAnswersWithoutModelOrUserPlaceMutation() {
        IntentResult result = service.answer("桃園機場在哪裡").orElseThrow();

        assertThat(result.message())
                .isEqualTo("我知道，您說的是位於桃園市的「臺灣桃園國際機場」。");
    }

    @Test
    void knownMetroStationAnswersWarmlyWithSystemRegionAndFormalName() {
        IntentResult result = service.answer("你知道捷運大坪林站嗎？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message()).isEqualTo(
                "我知道，您說的是「台北捷運」位於新北市的「捷運大坪林站」。");
    }

    @Test
    void everyApprovedCategoryAnswersWithoutPrematureClarification() {
        Map.of(
                        "捷運站", "202",
                        "港口", "7",
                        "機場", "17",
                        "高鐵站", "12",
                        "火車站", "244",
                        "縣市政府", "22",
                        "遊樂園", "27")
                .forEach((utterance, count) -> {
                    IntentResult result = service.answer(utterance).orElseThrow();
                    assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
                    assertThat(result.message()).contains(count).doesNotContain("？");
                });
    }

    @Test
    void userOwnedMentionKeepsPrecedenceOverSystemCatalog() {
        Place custom = Place.create(
                "我的桃園機場集合點", "桃園市測試路1號", 25.0, 121.2,
                "自訂集合點", Instant.parse("2030-01-01T00:00:00Z"));
        when(aliases.resolveMention("桃園機場在哪裡")).thenReturn(Optional.of(custom));

        IntentResult result = service.answer("桃園機場在哪裡").orElseThrow();

        assertThat(result.message()).contains("你的自訂地點", "我的桃園機場集合點")
                .doesNotContain("系統公共地點");
    }

    @Test
    void sameHubMultiplePointsAnswerThePlaceQuestionWithoutPlanningAssumptions() {
        IntentResult result = service.answer("台北站地點").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message()).contains("台北火車站", "2 個系統點位", "關鍵字");
        assertThat(result.message()).doesNotContain("現在不用先選", "安排完整行程時", "沒有建立");
        assertThat(result.message()).doesNotContain("？");
    }

    @Test
    void knowledgeQuestionAboutLogicalHubAnswersBeforeOfferingPointLookup() {
        IntentResult result = service.answer("你知道台北車站嗎？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message()).isEqualTo(
                "我知道，您說的是位於台北市的「台北火車站」。目前有 2 個系統點位，"
                        + "有需要我可以列出來給您，或者您也可以提供關鍵字讓我幫您查詢。");
        assertThat(result.message()).doesNotContain("安排完整行程", "現在不用先選", "沒有建立");
    }

    @Test
    void directLogicalHubPointQuestionListsEveryPointWithoutItineraryLanguage() {
        IntentResult result = service.answer("台北車站有哪些點位？").orElseThrow();

        assertThat(result.message()).contains("目前有 2 個系統點位", "台北捷運", "桃園捷運");
        assertThat(result.message()).doesNotContain("安排完整行程", "哪個縣市");
    }

    @Test
    void directLogicalHubKeywordQuestionFiltersBeforeReplying() {
        IntentResult result = service.answer("台北車站有哪些機場捷運點位？").orElseThrow();

        assertThat(result.message())
                .isEqualTo("我知道，您說的是「桃園捷運」位於台北市的「捷運台北車站」。")
                .doesNotContain("找到 1 個", "台北捷運", "安排完整行程", "現在不用先選");
    }

    @Test
    void contextFreePointFollowUpAsksExactlyOnePlaceQuestion() {
        IntentResult result = service.answer("有哪些點位？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.nextQuestion().code()).isEqualTo("place.point-location");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void crossRegionAmbiguityAsksExactlyOneCountyQuestion() {
        IntentResult result = service.answer("市政府站在哪裡").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code()).isEqualTo("place.system-region");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void scheduleMutationRemainsForTheTypedCalendarPath() {
        assertThat(service.answer("明天下午兩點幫我安排在台北站搭車")).isEmpty();
    }

    @Test
    void completeTimedCalendarTurnWithoutAnExplicitCreateVerbStillBypassesPlaceLookup() {
        Place custom = Place.create(
                "候選地點", "測試地址", 25.0, 121.2,
                "自訂地點", Instant.parse("2030-01-01T00:00:00Z"));
        when(aliases.resolveMention("9月21日下午三點到四點在候選地點開會"))
                .thenReturn(Optional.of(custom));

        assertThat(service.answer("9月21日下午三點到四點在候選地點開會")).isEmpty();
    }

    @Test
    void timedExplicitPlaceQuestionRemainsAReadOnlyPlaceLookup() {
        IntentResult result = service.answer("明天下午三點桃園機場在哪裡").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message())
                .isEqualTo("我知道，您說的是位於桃園市的「臺灣桃園國際機場」。");
    }

    @Test
    void temporalPropertyQuestionAboutSystemPlaceIsNotReducedToAddressLookup() {
        assertThat(service.answer("明天台北車站最晚幾點還有車")).isEmpty();
    }

    @Test
    void originDestinationStatementRemainsForRouteInterpretation() {
        assertThat(service.answer("明天早上九點從台北車站到桃園機場")).isEmpty();
    }

    @Test
    void structuredDateAndLocationStatementRemainsForEventIntake() {
        assertThat(service.answer("研討會門票開賣；活動日期：2026年9月11日；活動地點：會議中心"))
                .isEmpty();
    }

    @Test
    void customPlaceWithoutAddressDoesNotHideSpecializedHumanGuidance() {
        Place custom = Place.create(
                "測試幼兒園", null, 25.0, 121.2,
                "學校", Instant.parse("2030-01-01T00:00:00Z"));
        when(aliases.resolveMention("你知道測試幼兒園在哪嗎"))
                .thenReturn(Optional.of(custom));

        assertThat(service.answer("你知道測試幼兒園在哪嗎")).isEmpty();
    }
}
