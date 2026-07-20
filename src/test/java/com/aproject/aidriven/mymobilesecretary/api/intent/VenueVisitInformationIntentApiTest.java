package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class VenueVisitInformationIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Test
    void originalInsectMuseumMessageSavesKnowledgeAndDecoratesFutureSchedule() throws Exception {
        say("這是台灣昆蟲館的參觀資訊，幫我記得，下次要去台灣昆蟲館的時候提醒我還有這個活動可以預約。",
                "VENUE_VISIT_INFO_SAVED", "台灣昆蟲館", "沒有替你建立行程或完成預約");

        stub.nextCommand(new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE,
                "去台灣昆蟲館", null,
                "2030-08-10T09:00:00+08:00", "2030-08-10T11:00:00+08:00",
                null, null, null, null, null, null, null, null));

        say("2030年8月10日9點到11點去台灣昆蟲館", "SCHEDULE_CONFIRMED",
                "你先前保存的參觀提醒", "活動可以預約", "最新公告");
    }

    private void say(String text, String action, String... messageParts) throws Exception {
        var result = mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "%s"}
                                """.formatted(text)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value(action));
        for (String part : messageParts) {
            result.andExpect(jsonPath("$.message").value(containsString(part)));
        }
    }
}
