package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogAdoption;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogLookup;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogService;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogView;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.NearbySuggestionService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceIntentHandlerTest {

    private PlaceIntentHandler handler;
    private PlaceAliasService aliases;
    private PlaceService places;
    private SystemPlaceCatalogService catalogService;

    @BeforeEach
    void setUp() {
        aliases = mock(PlaceAliasService.class);
        places = mock(PlaceService.class);
        catalogService = mock(SystemPlaceCatalogService.class);
        handler = new PlaceIntentHandler(
                mock(TaskService.class),
                aliases,
                places,
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
    void unknownPlaceAsksOneTypedDisambiguationWithoutOfferingToCreateData() {
        when(places.lookupPublicPlace("全聯"))
                .thenReturn(PlaceService.PublicPlaceLookup.notFound());

        IntentResult result = handler.handle("全聯在哪", command(IntentCommand.Type.ASK_PLACE, "全聯"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message())
                .contains("系統公共地點", "沒有建立或修改", "哪個縣市、運輸系統")
                .doesNotContain("建立地點:");
        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code()).isEqualTo("place.lookup-disambiguation");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
    }

    @Test
    void unknownLocalPlaceReturnsPublicAddressWithoutPersistingIt() {
        when(places.lookupPublicPlace("測試商場"))
                .thenReturn(PlaceService.PublicPlaceLookup.found(
                        "測試商場", "台北市測試區安全路1號", "購物中心"));

        IntentResult result = handler.handle(
                "測試商場在哪", command(IntentCommand.Type.ASK_PLACE, "測試商場"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message())
                .contains("台北市測試區安全路1號", "尚未儲存")
                .doesNotContain("建立成功");
    }

    @Test
    void systemOwnedPublicPlaceIsReportedWithoutCreatingACustomPlace() {
        when(places.lookupPublicPlace("桃園機場"))
                .thenReturn(PlaceService.PublicPlaceLookup.foundInSystemCatalog(
                        "臺灣桃園國際機場", "桃園市大園區航站南路9號", "AIRPORT"));

        IntentResult result = handler.handle(
                "桃園機場在哪", command(IntentCommand.Type.ASK_PLACE, "桃園機場"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_INFO);
        assertThat(result.message())
                .contains("系統公共地點資料", "機場", "沒有建立或修改你的自訂地點")
                .doesNotContain("AIRPORT", "建立成功");
    }

    @Test
    void createPlacePreservesExplicitAddressInsteadOfNameOnlyLookup() {
        String address = "新北市測試區安全路88號";
        Place created = Place.create(
                "測試門市", address, 24.9, 121.5, "商店", Instant.parse("2026-07-31T08:00:00Z"));
        when(aliases.resolve("測試門市")).thenReturn(Optional.empty());
        when(places.createPlace(any(), any(), any(), any(), any())).thenReturn(created);
        IntentOptions options = new IntentOptions(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, address);
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_PLACE,
                null,
                null,
                null,
                null,
                "測試門市",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                options,
                address);

        IntentResult result = handler.handle(address, command);

        verify(places).createPlace("測試門市", address, null, null, null);
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CREATED);
        assertThat(result.message()).contains(address);
    }

    @Test
    void createPlaceExtractsAFullExplicitAddressWhenTheModelOmitsDescription() {
        String address = "新北市測試區安全路88號";
        String text = "請建立測試門市，地址是" + address;
        Place created = Place.create(
                "測試門市", address, 24.9, 121.5, "商店", Instant.parse("2026-07-31T08:00:00Z"));
        when(aliases.resolve("測試門市")).thenReturn(Optional.empty());
        when(places.createPlace(any(), any(), any(), any(), any())).thenReturn(created);
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_PLACE,
                null,
                null,
                null,
                null,
                "測試門市",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty(),
                text);

        IntentResult result = handler.handle(text, command);

        verify(places).createPlace("測試門市", address, null, null, null);
        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CREATED);
    }

    @Test
    void exactCatalogPlaceAnswersWithoutCustomPlaceDisclaimer() {
        var point = point("station.main", "台北車站南一門");
        when(catalogService.lookup("台北車站", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.EXACT,
                        "台北車站", null, java.util.List.of(point)));

        IntentResult result = handler.handle(
                "系統知道台北車站嗎",
                command(IntentCommand.Type.ASK_PLACE_CATALOG, "台北車站"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CATALOG_INFO);
        assertThat(result.message())
                .contains("台北車站南一門", "approved-test-fixture")
                .doesNotContain("自訂地點");
        verifyNoInteractions(places);
    }

    @Test
    void multipointCatalogPlaceAsksOneTypedSelectionQuestion() {
        var first = point("hub.first", "第一航廈");
        var second = point("hub.second", "第二航廈");
        when(catalogService.lookup("桃園機場", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.MULTIPOINT,
                        "桃園機場", null, java.util.List.of(first, second)));

        IntentResult result = handler.handle(
                "桃園機場有哪個點",
                command(IntentCommand.Type.ASK_PLACE_CATALOG, "桃園機場"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("第一航廈", "第二航廈");
        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code()).isEqualTo("place.catalog-selection");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
        verifyNoInteractions(places);
    }

    @Test
    void adoptingCatalogEvidenceDoesNotCreateCustomPlace() {
        var point = point("station.main", "台北車站南一門");
        when(catalogService.lookup("台北車站南一門", null)).thenReturn(
                new SystemPlaceCatalogLookup(SystemPlaceCatalogLookup.Status.EXACT,
                        "台北車站南一門", null, java.util.List.of(point)));
        when(catalogService.adopt("台北車站南一門", null, null)).thenReturn(
                new SystemPlaceCatalogAdoption(point, false, false));

        IntentResult result = handler.handle(
                "採用台北車站南一門",
                command(IntentCommand.Type.ADOPT_PLACE_CATALOG, "台北車站南一門"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PLACE_CATALOG_ADOPTED);
        assertThat(result.message()).contains("已選用", "台北車站南一門")
                .doesNotContain("建立自訂地點");
        verifyNoInteractions(places);
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
