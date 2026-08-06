package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogAdoption;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogLookup;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogService;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogView;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.NearbySuggestionService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceIntentHandlerTest {

    private PlaceIntentHandler handler;
    private SystemPlaceCatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = mock(SystemPlaceCatalogService.class);
        when(catalogService.lookup(anyString(), any())).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.NOT_FOUND,
                        "", null, java.util.List.of()));
        handler = new PlaceIntentHandler(
                mock(TaskService.class),
                mock(PlaceAliasService.class),
                mock(PlaceService.class),
                mock(GeofenceRuleService.class),
                mock(NearbySuggestionService.class),
                mock(ConversationContextService.class),
                mock(FamilyMessageService.class),
                catalogService,
                200);
    }

    @Test
    void registersEveryPlaceAndGeofenceType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.ASK_PLACE,
                IntentCommand.Type.ASK_PLACE_CATALOG,
                IntentCommand.Type.ADOPT_PLACE_CATALOG,
                IntentCommand.Type.CREATE_PLACE,
                IntentCommand.Type.UPDATE_PLACE,
                IntentCommand.Type.BIND_TASK_PLACE,
                IntentCommand.Type.ASK_TASK_PLACE,
                IntentCommand.Type.SUGGEST_NEARBY,
                IntentCommand.Type.SET_PLACE_ALIAS,
                IntentCommand.Type.LIST_LOCATION_TASKS,
                IntentCommand.Type.ASK_PLACE_TASKS,
                IntentCommand.Type.ASK_TASK_GEOFENCE,
                IntentCommand.Type.UPDATE_TASK_GEOFENCE,
                IntentCommand.Type.REMOVE_TASK_PLACE));
    }

    @Test
    void unknownPlaceKeepsExistingClarification() {
        IntentResult result = handler.handle("全聯在哪", command(IntentCommand.Type.ASK_PLACE, "全聯"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).isEqualTo(IntentResult.clarificationNeeded(
                "我沒有叫「全聯」的地點紀錄,說「建立地點:全聯」我就去 Google 查來存。"
        ).message());
    }

    @Test
    void exactCatalogPlaceAnswersWithoutCustomPlaceDisclaimer() {
        var point = point("station.main", "台北車站南一門");
        when(catalogService.lookup("台北車站", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.EXACT,
                        "台北車站", null, Set.of(point).stream().toList()));

        IntentResult result = handler.handle("系統知道台北車站嗎",
                command(IntentCommand.Type.ASK_PLACE_CATALOG, "台北車站"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CATALOG_INFO);
        assertThat(result.message()).contains("台北車站南一門", "approved-test-fixture")
                .doesNotContain("自訂地點");
    }

    @Test
    void multipointCatalogPlaceAsksForSelection() {
        var first = point("hub.first", "第一航廈");
        var second = point("hub.second", "第二航廈");
        when(catalogService.lookup("桃園機場", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.MULTIPOINT,
                        "桃園機場", null, java.util.List.of(first, second)));

        IntentResult result = handler.handle("桃園機場有哪個點",
                command(IntentCommand.Type.ASK_PLACE_CATALOG, "桃園機場"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("多個", "第一航廈", "第二航廈");
    }

    @Test
    void adoptingCatalogEvidenceDoesNotCreateCustomPlace() {
        var point = point("station.main", "台北車站南一門");
        when(catalogService.lookup("台北車站南一門", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.EXACT,
                        "台北車站南一門", null, java.util.List.of(point)));
        when(catalogService.adopt("台北車站南一門", null, null)).thenReturn(
                new SystemPlaceCatalogAdoption(point, false, false));

        IntentResult result = handler.handle("採用台北車站南一門",
                command(IntentCommand.Type.ADOPT_PLACE_CATALOG, "台北車站南一門"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CATALOG_ADOPTED);
        assertThat(result.message()).contains("已選用", "台北車站南一門")
                .doesNotContain("建立自訂地點");
    }

    private static SystemPlaceCatalogView point(String key, String pointName) {
        return new SystemPlaceCatalogView(key, key, "測試地點", pointName, "臺北市",
                "測試路 1 號", 25.0, 121.5, "TEST", "approved-test-fixture", null);
    }

    private static IntentCommand command(IntentCommand.Type type, String placeName) {
        return new IntentCommand(type, null, null, null, null, placeName, null, null,
                null, null, null, null, null);
    }
}
