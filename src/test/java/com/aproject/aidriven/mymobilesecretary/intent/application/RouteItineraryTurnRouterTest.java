package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteItineraryTurnRouterTest {

    @ParameterizedTest
    @CsvSource({
        "幫我規劃明天九點由我的位置出發到高鐵桃園站,我的位置",
        "幫我安排明天九點自目前所在地啟程前往高鐵桃園站,目前所在地",
        "幫我規劃明天九點以目前位置為起點到高鐵桃園站,目前位置",
        "幫我規畫明天九點從這裡開始走到高鐵桃園站,這裡",
        "幫我建立行程明天九點由公司動身前往高鐵桃園站,公司",
        "幫我規劃明天九點從公司這邊出發到高鐵桃園站,公司這邊"
    })
    void groundsComposedOriginWordingBeforeTypedOriginPolicy(
            String phrase, String expectedOrigin) {
        assertThat(RouteItineraryTurnRouter.route(phrase))
                .hasValueSatisfying(decision -> {
                    assertThat(decision.origin()).isEqualTo(expectedOrigin);
                    assertThat(decision.destination()).isEqualTo("高鐵桃園站");
                });
    }

    @Test
    void explicitOriginDestinationArrangementProducesTypedRouteDecision() {
        RouteItineraryTurnRouter.Decision decision = RouteItineraryTurnRouter.route(
                "明天早上九點從台北車站到桃園機場，幫我安排行程").orElseThrow();

        assertThat(decision.kind())
                .isEqualTo(RouteItineraryTurnRouter.Kind.PLAN_TRANSPORT_ITINERARY);
        assertThat(decision.origin()).isEqualTo("台北車站");
        assertThat(decision.destination()).isEqualTo("桃園機場");
    }

    @Test
    void planningAndArrangementSynonymsPreserveTheSameExplicitEndpoints() {
        var planning = RouteItineraryTurnRouter.route(
                "幫我規劃明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程")
                .orElseThrow();
        var arrangement = RouteItineraryTurnRouter.route(
                "幫我安排明天早上九點從捷運大坪林到桃園捷運「捷運台北車站」的行程")
                .orElseThrow();

        assertThat(planning.kind()).isEqualTo(arrangement.kind());
        assertThat(planning.origin()).isEqualTo("捷運大坪林");
        assertThat(planning.destination()).isEqualTo("桃園捷運「捷運台北車站」");
        assertThat(arrangement.origin()).isEqualTo(planning.origin());
        assertThat(arrangement.destination()).isEqualTo(planning.destination());
    }

    @Test
    void readOnlyPlaceAndTravelTimeQuestionsRemainNeighborCapabilities() {
        assertThat(RouteItineraryTurnRouter.route("桃園機場在哪裡")).isEmpty();
        assertThat(RouteItineraryTurnRouter.route("從公司到桃園機場要多久")).isEmpty();
    }
}
