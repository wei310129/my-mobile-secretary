package com.aproject.aidriven.mymobilesecretary.api.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 品項知識 API 整合測試。
 */
class ItemApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private long createPlace(String name) throws Exception {
        String body = mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "latitude": 20.50, "longitude": 120.10}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void createItemReturns201() throws Exception {
        long placeId = createPlace("品項測試店-1");

        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "蛤蜊", "placeIds": [%d]}
                                """.formatted(placeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("蛤蜊"))
                .andExpect(jsonPath("$.placeIds[0]").value(placeId));
    }

    /** 品項名重複 → 422。 */
    @Test
    void duplicateItemNameReturns422() throws Exception {
        long placeId = createPlace("品項測試店-2");
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "白蘿蔔", "placeIds": [%d]}
                                """.formatted(placeId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "白蘿蔔", "placeIds": [%d]}
                                """.formatted(placeId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ITEM"));
    }

    /** 指向不存在的地點 → 404,不得建立。 */
    @Test
    void unknownPlaceReturns404() throws Exception {
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "幽靈品項", "placeIds": [999999]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 名稱空白或沒給地點 → 400。 */
    @Test
    void invalidRequestReturns400() throws Exception {
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "placeIds": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listItemsReturnsCreated() throws Exception {
        long placeId = createPlace("品項測試店-3");
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Tabasco", "placeIds": [%d]}
                                """.formatted(placeId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").exists());
    }
}
