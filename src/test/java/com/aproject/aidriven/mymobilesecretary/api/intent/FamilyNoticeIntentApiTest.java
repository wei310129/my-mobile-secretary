package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Real controller -> deterministic family intake -> JPA/PostgreSQL flow. */
class FamilyNoticeIntentApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService placeService;

    @Test
    void forwardedTeacherNoticePersistsDraftAndStatusWithoutUsingGeneralIntentModel()
            throws Exception {
        String notice = """
                明日是大女兒幼兒園的父親節活動，以下是老師的提醒訊息：
                @All 親愛的家長，您好
                1.明天小朋友穿運動服
                2.請家長們明天10:00報到
                3.明天早上彩虹門會開
                4.滬江高中停車位有限，請儘量搭乘大眾運輸
                5.活動風雨無阻
                6.大人小孩都要帶換洗衣物，請穿防水鞋
                7.被子、室內鞋、漱口杯及牙刷星期一帶回來
                """;

        say(notice,
                jsonPath("$.action").value("FAMILY_NOTICE_DRAFTED"),
                jsonPath("$.message").value(containsString("父親節活動")),
                jsonPath("$.message").value(containsString("活動結束時間")),
                jsonPath("$.schedule").doesNotExist(),
                jsonPath("$.task").doesNotExist());

        say("你有把老師的提醒幫我加到行程了嗎？",
                jsonPath("$.action").value("FAMILY_NOTICE_STATUS"),
                jsonPath("$.message").value(containsString("還沒有建立正式行程")));

        say("確認老師通知",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(containsString("活動結束時間")));

        String mixedProductRequirement = """
                明天的父親節活動是12點結束，而且結束後女兒同學的爸爸有約大家一起午餐。

                對了，每天一般都有早餐、午餐、晚餐等生活硬需求，當時間太緊的時候你要提醒要預留用餐行程，這比較不算一個行程，比較像生活建議，就好比會影響睡覺時間。給使用者決定後只要註記會受到影響即可。

                但前提是每個人的三餐時間並不一樣，你要先和使用者確認想要的時間。固定行程可能有類似設定，要有個優先順序來整合，例如上班日和週末的早餐時間不同。
                """;
        say(mixedProductRequirement,
                jsonPath("$.action").value("FEEDBACK_RECEIVED"),
                jsonPath("$.message").value(containsString("不會建立待辦或行程")));

        // Product feedback must not consume the embedded 12:00 or mutate the pending draft.
        say("確認老師通知",
                jsonPath("$.action").value("CLARIFICATION_NEEDED"),
                jsonPath("$.message").value(containsString("活動結束時間")));

        say("你沒有聽懂",
                jsonPath("$.action").value("FEEDBACK_RECEIVED"),
                jsonPath("$.message").value(containsString("理解錯了")));

        say("12點結束",
                jsonPath("$.action").value("FAMILY_NOTICE_DRAFTED"),
                jsonPath("$.message").value(containsString("12:00")),
                jsonPath("$.message").value(containsString("確認老師通知")));

        say("確認老師通知",
                jsonPath("$.action").value("FAMILY_NOTICE_CONFIRMED"),
                jsonPath("$.message").value(containsString("已確認並加入行程")),
                jsonPath("$.message").value(containsString("準備待辦")));
    }

    @Test
    void explicitFamilyRelationshipIsStoredWithoutCreatingTaskOrSchedule() throws Exception {
        say("我是我女兒的家長，也是我女兒的爸爸，這層關係你能理解嗎？",
                jsonPath("$.action").value("KNOWLEDGE_SAVED"),
                jsonPath("$.message").value(containsString("爸爸")),
                jsonPath("$.schedule").doesNotExist(),
                jsonPath("$.task").doesNotExist());
    }

    @Test
    void userTaughtEntranceIsReturnedAsHumanGuidanceWithoutCoordinates() throws Exception {
        placeService.createPlace("滬江測試幼兒園", null,
                24.98967, 121.53958, "學校");

        say("滬江測試的彩虹門位於「景美便堤」的後門，專門給開車接送家長使用。",
                jsonPath("$.action").value("KNOWLEDGE_SAVED"));

        say("你知道滬江測試幼兒園在哪嗎？",
                jsonPath("$.action").value("PLACE_INFO"),
                jsonPath("$.message").value(containsString("我知道")),
                jsonPath("$.message").value(containsString("景美便堤")),
                jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("24.98967"))),
                jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("座標"))));
    }

    private void say(String text, org.springframework.test.web.servlet.ResultMatcher... matchers)
            throws Exception {
        var action = mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isOk());
        for (var matcher : matchers) {
            action.andExpect(matcher);
        }
    }
}
