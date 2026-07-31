package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
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

    @BeforeEach
    void setUp() {
        aliases = mock(PlaceAliasService.class);
        places = mock(PlaceService.class);
        handler = new PlaceIntentHandler(
                mock(TaskService.class),
                aliases,
                places,
                mock(GeofenceRuleService.class),
                mock(NearbySuggestionService.class),
                mock(ConversationContextService.class),
                mock(FamilyMessageService.class),
                200);
    }

    @Test
    void registersEveryPlaceAndGeofenceType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.ASK_PLACE,
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

    private static IntentCommand command(IntentCommand.Type type, String placeName) {
        return new IntentCommand(type, null, null, null, null, placeName, null, null,
                null, null, null, null, null);
    }
}
