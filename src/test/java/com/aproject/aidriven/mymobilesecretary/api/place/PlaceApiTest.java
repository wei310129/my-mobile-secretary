package com.aproject.aidriven.mymobilesecretary.api.place;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 地點 API 整合測試。
 */
class PlaceApiTest extends IntegrationTestBase {

    @Test
    void createPlaceReturns201() throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "全聯福利中心",
                                  "address": "台北市某路 1 號",
                                  "latitude": 25.0330,
                                  "longitude": 121.5654,
                                  "type": "超市"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("全聯福利中心"))
                .andExpect(jsonPath("$.latitude").value(25.0330))
                .andExpect(jsonPath("$.type").value("超市"));
    }

    /** 緯度超出 ±90 → 400。 */
    @Test
    void createPlaceWithInvalidLatitudeReturns400() throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "壞座標", "latitude": 91.0, "longitude": 121.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("latitude"));
    }

    /** 名稱空白 → 400。 */
    @Test
    void createPlaceWithBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "latitude": 25.0, "longitude": 121.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listPlacesReturnsCreatedPlace() throws Exception {
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "菜市場", "latitude": 25.04, "longitude": 121.55, "type": "市場"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNumber());
    }
}
