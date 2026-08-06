package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.domain.SystemPlaceCatalogEntry;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.persistence.SystemPlaceCatalogRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SystemPlaceCatalogIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private SystemPlaceCatalogRepository catalogRepository;

    @BeforeEach
    void seedApprovedFixtureOnly() {
        catalogRepository.deleteAll();
        catalogRepository.save(SystemPlaceCatalogEntry.create(
                "fixture.catalog.hub", "fixture.catalog.hub", "測試轉運站", "測試轉運站",
                "臺北市", "測試路 2 號", null, null, "TRANSIT",
                "approved-test-fixture", null, Instant.EPOCH));
    }

    @Test
    void conversationQueriesAndAdoptsCatalogEvidenceWithoutCustomPlace() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PLACE_CATALOG, null, null, null, null, "測試轉運站",
                null, null, null, null, null, null, null));
        say("系統知道測試轉運站嗎", "PLACE_CATALOG_INFO");

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ADOPT_PLACE_CATALOG, null, null, null, null, "測試轉運站",
                null, null, null, null, null, null, null));
        say("採用測試轉運站", "PLACE_CATALOG_ADOPTED");
    }

    private void say(String text, String action) throws Exception {
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"%s\"}".formatted(text)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value(action));
    }
}
