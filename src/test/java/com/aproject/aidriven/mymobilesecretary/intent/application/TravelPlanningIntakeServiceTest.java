package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TravelPlanningIntakeServiceTest {

    private final TravelPlanningIntakeService service = new TravelPlanningIntakeService(35);

    @Test
    void cruiseExampleCollectsInternationalTripDetailsWithoutMutation() {
        IntentResult result = service.answer("11月17-22日搭郵輪去日本玩").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.TRAVEL_INFO);
        assertThat(result.message()).contains(
                "出國旅遊", "11/17–11/22", "日本", "郵輪", "出發時間與地點",
                "返家方式", "準備任務與期限", "確認後才建立");
        assertThat(result.task()).isNull();
        assertThat(result.decision()).isNull();
    }

    @Test
    void drivingWithoutDestinationAsksInsteadOfGuessing() {
        IntentResult result = service.answer("我想開車出去玩").orElseThrow();

        assertThat(result.message()).contains("35 分鐘", "目的地是哪裡", "單程多久");
    }

    @Test
    void nearbyParkIsDailyLeisureRatherThanTravel() {
        IntentResult result = service.answer("明天去家附近公園走走").orElseThrow();

        assertThat(result.message()).contains("日常休閒", "不納入國內出遊或出國旅遊");
    }

    @Test
    void productProposalAndHistoryQuestionAreNotExecutedAsTripPlans() {
        assertThat(service.answer(
                "功能改善：例如你要能處理11月17-22日搭郵輪去日本玩，並詢問出發地點"))
                .isEmpty();
        assertThat(service.answer("我上次出國玩是什麼時候？")).isEmpty();
    }
}
