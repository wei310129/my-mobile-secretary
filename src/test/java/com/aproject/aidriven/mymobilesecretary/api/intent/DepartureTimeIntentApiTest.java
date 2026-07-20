package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Scenario #159: latest departure uses an explicit origin and deterministic buffers. */
class DepartureTimeIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private PlanningPreferenceService preferenceService;

    @Test
    void computesLatestDepartureFromHomeWithoutRequiringCurrentGps() throws Exception {
        createPlace("家", 24.9820, 121.5360);
        createPlace("台大醫院", 25.0417, 121.5190);
        preferenceService.setBuffers(15, 0);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_DEPARTURE_TIME, null,
                "2026-07-22T10:00:00+08:00", null, null, "台大醫院",
                null, null, null, null, null, null, null,
                IntentOptions.empty().withDepartureOrigin("家", null)));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"我下禮拜三上午要去台大醫院，大概十點前到就好，從家裡開車，幫我抓最晚幾點出門"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TRAVEL_INFO"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("從「家」最晚")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("基本轉場緩衝 10 分鐘")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("另保留 15 分鐘停車／抵達緩衝")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("10:00 前到「台大醫院」")));
    }

    private void createPlace(String name, double latitude, double longitude) throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","latitude":%s,"longitude":%s}
                                """.formatted(name, latitude, longitude)))
                .andExpect(status().isCreated());
    }
}
