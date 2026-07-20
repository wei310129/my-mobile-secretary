package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
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

    @BeforeEach
    void setUp() {
        handler = new PlaceIntentHandler(
                mock(TaskService.class),
                mock(PlaceAliasService.class),
                mock(PlaceService.class),
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

    private static IntentCommand command(IntentCommand.Type type, String placeName) {
        return new IntentCommand(type, null, null, null, null, placeName, null, null,
                null, null, null, null, null);
    }
}
