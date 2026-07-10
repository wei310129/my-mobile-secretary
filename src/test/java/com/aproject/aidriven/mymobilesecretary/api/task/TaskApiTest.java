package com.aproject.aidriven.mymobilesecretary.api.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 任務 API 整合測試:走真實 controller → service → domain → PostGIS。
 */
class TaskApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    /** 建立一個任務並回傳其 id,供各測試重複使用。 */
    private long createTask() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "買排骨", "description": "菜市場 10 點前"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asLong();
    }

    @Test
    void createTaskReturns201WithCreatedStatusAndDefaultPriority() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "帶 12 色蠟筆", "dueAt": "2026-07-11T02:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("帶 12 色蠟筆"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                // 未指定 priority 時預設 NORMAL
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.dueAt").value("2026-07-11T02:00:00Z"));
    }

    @Test
    void createTaskWithBlankTitleReturns400WithFieldViolation() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    }

    @Test
    void listTasksReturnsCreatedTask() throws Exception {
        createTask();

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    void getTaskReturnsTask() throws Exception {
        long id = createTask();

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("買排骨"));
    }

    @Test
    void getUnknownTaskReturns404() throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 路徑參數不是數字 → 400(不能誤判成 500)。 */
    @Test
    void nonNumericTaskIdReturns400() throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void confirmTaskMovesToConfirmed() throws Exception {
        long id = createTask();

        mockMvc.perform(patch("/api/tasks/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmUnknownTaskReturns404() throws Exception {
        mockMvc.perform(patch("/api/tasks/{id}/confirm", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 已確認的任務再確認 → 422 非法狀態轉換。 */
    @Test
    void confirmTwiceReturns422() throws Exception {
        long id = createTask();
        mockMvc.perform(patch("/api/tasks/{id}/confirm", id)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/tasks/{id}/confirm", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void cancelTaskMovesToCanceled() throws Exception {
        long id = createTask();

        mockMvc.perform(patch("/api/tasks/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    /** 已取消的任務不可再確認 → 422。 */
    @Test
    void confirmCanceledTaskReturns422() throws Exception {
        long id = createTask();
        mockMvc.perform(patch("/api/tasks/{id}/cancel", id)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/tasks/{id}/confirm", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }
}
