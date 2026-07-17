package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Controller -> private family identity -> alias/attribute persistence flow. */
class FamilyPersonIntentApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void recognizesActorsPrivateFamilyVocabularyWithoutAi() throws Exception {
        say("你能分辨我老婆、大女兒和小兒子嗎？",
                jsonPath("$.action").value("FAMILY_PERSON_RECOGNIZED"),
                jsonPath("$.message").value(containsString("老婆（姓名尚未提供）")),
                jsonPath("$.message").value(containsString("大女兒（姓名尚未提供）")),
                jsonPath("$.message").value(containsString("小兒子（姓名尚未提供）")),
                jsonPath("$.message").value(containsString("不會因未來開啟家庭功能就自動共享")));

        say("你知道我女兒是誰嗎？",
                jsonPath("$.action").value("FAMILY_PERSON_RECOGNIZED"),
                jsonPath("$.message").value(containsString("大女兒")),
                jsonPath("$.message").value(not(containsString("小兒子"))));

        say("你知道我兒子是誰嗎？",
                jsonPath("$.action").value("FAMILY_PERSON_RECOGNIZED"),
                jsonPath("$.message").value(containsString("小兒子")));
    }

    @Test
    void explicitlyTaughtNameStaysPrivateAndCanBeRecalled() throws Exception {
        say("我老婆叫小美",
                jsonPath("$.action").value("FAMILY_PERSON_UPDATED"),
                jsonPath("$.message").value(containsString("姓名：小美")),
                jsonPath("$.message").value(containsString("只有你")));

        say("我老婆叫什麼名字？",
                jsonPath("$.action").value("FAMILY_PERSON_RECOGNIZED"),
                jsonPath("$.message").value(containsString("老婆（姓名：小美）")));
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
