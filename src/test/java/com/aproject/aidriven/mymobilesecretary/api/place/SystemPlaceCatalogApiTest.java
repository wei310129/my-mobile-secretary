package com.aproject.aidriven.mymobilesecretary.api.place;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.domain.SystemPlaceCatalogEntry;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.persistence.SystemPlaceCatalogRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SystemPlaceCatalogApiTest extends IntegrationTestBase {

    @Autowired
    private SystemPlaceCatalogRepository catalogRepository;

    @BeforeEach
    void clearCatalogFixture() {
        catalogRepository.deleteAll();
    }

    @Test
    void lookupAndAdoptDoNotCreateCustomPlace() throws Exception {
        catalogRepository.save(SystemPlaceCatalogEntry.create(
                "fixture.station.main", "fixture.station", "測試車站", "測試車站南門",
                "臺北市", "測試路 1 號", 25.03, 121.56, "STATION",
                "approved-test-fixture", null, Instant.EPOCH));

        mockMvc.perform(get("/api/place-catalog").param("query", "測試車站"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXACT"))
                .andExpect(jsonPath("$.candidates[0].catalogKey").value("fixture.station.main"))
                .andExpect(jsonPath("$.candidates[0].coordinatesPresent").value(true));

        mockMvc.perform(post("/api/place-catalog/fixture.station.main/adopt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point.catalogKey").value("fixture.station.main"))
                .andExpect(jsonPath("$.customPlaceCreated").value(false))
                .andExpect(jsonPath("$.copiedToPlanSnapshot").value(false));

        mockMvc.perform(get("/api/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
