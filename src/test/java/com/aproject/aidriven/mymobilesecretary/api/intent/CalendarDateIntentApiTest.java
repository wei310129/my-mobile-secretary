package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Scenario #158: a yearless date that conflicts with its stated weekday is fail-closed. */
class CalendarDateIntentApiTest extends IntegrationTestBase {

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void conflictingDateAndWeekdayNeverReachesTheModelOrCreatesSchedule() throws Exception {
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"老師說下週戶外教學但日期寫成7/31星期四，我看日曆好像不是星期四，你先幫我確認不要加"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CLARIFICATION_NEEDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("2026/07/31（五）")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不是星期四")));

        assertThat(scheduleService.findReschedulableSchedulesMatching("戶外教學")).isEmpty();
    }
}
