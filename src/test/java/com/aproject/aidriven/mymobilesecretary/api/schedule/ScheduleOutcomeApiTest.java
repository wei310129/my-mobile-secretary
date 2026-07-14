package com.aproject.aidriven.mymobilesecretary.api.schedule;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 行程結果 API 測試:回報成功、驗證失敗、行程不存在、重複回報、查詢。
 *
 * 測試行程一律結束在 24 小時 lookback 之外,避免被追蹤詢問的
 * planFollowUpsForEndedSchedules 撈走,干擾其他測試的「最近發問」判定。
 */
class ScheduleOutcomeApiTest extends IntegrationTestBase {

    @Autowired
    private ScheduleItemRepository scheduleItemRepository;

    /** 建一個 25 小時前結束的已確認行程(lookback 之外)。 */
    private ScheduleItem saveEndedSchedule(String title) {
        Instant end = Instant.now().minus(Duration.ofHours(25));
        ScheduleItem item = ScheduleItem.propose(title, end.minus(Duration.ofHours(1)), end, null, end);
        item.confirm(end);
        return scheduleItemRepository.save(item);
    }

    /** 回報超時 → 201,行程自動 COMPLETED。 */
    @Test
    void recordOverrunOutcomeSucceeds() throws Exception {
        ScheduleItem item = saveEndedSchedule("回診");

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"onTime": false, "overrunMinutes": 20, "reason": "RUSH_HOUR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onTime").value(false))
                .andExpect(jsonPath("$.overrunMinutes").value(20))
                .andExpect(jsonPath("$.reason").value("RUSH_HOUR"));

        mockMvc.perform(get("/api/schedules/{id}", item.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    /** 回報準時 → 201,超時欄位為空。 */
    @Test
    void recordOnTimeOutcomeSucceeds() throws Exception {
        ScheduleItem item = saveEndedSchedule("剪頭髮");

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onTime").value(true))
                .andExpect(jsonPath("$.overrunMinutes").doesNotExist());
    }

    /** onTime 缺 → 400 validation。 */
    @Test
    void missingOnTimeFailsValidation() throws Exception {
        ScheduleItem item = saveEndedSchedule("驗證測試");

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrunMinutes\": 20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 超時但沒給分鐘數 → domain 擋下(422)。 */
    @Test
    void overrunWithoutMinutesIsRejected() throws Exception {
        ScheduleItem item = saveEndedSchedule("缺分鐘數");

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": false}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OVERRUN"));
    }

    /** 行程不存在 → 404。 */
    @Test
    void unknownScheduleReturns404() throws Exception {
        mockMvc.perform(post("/api/schedules/{id}/outcome", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": true}"))
                .andExpect(status().isNotFound());
    }

    /** 重複回報 → 422 業務錯誤。 */
    @Test
    void duplicateOutcomeIsRejected() throws Exception {
        ScheduleItem item = saveEndedSchedule("重複回報");

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": true}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTCOME_ALREADY_RECORDED"));
    }

    /** 尚未回報就查 → 404;回報後可查。 */
    @Test
    void getOutcomeBeforeAndAfterRecording() throws Exception {
        ScheduleItem item = saveEndedSchedule("查詢測試");

        mockMvc.perform(get("/api/schedules/{id}/outcome", item.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/schedules/{id}/outcome", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onTime\": true, \"note\": \"準時結束\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/schedules/{id}/outcome", item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onTime").value(true))
                .andExpect(jsonPath("$.note").value("準時結束"));
    }
}
