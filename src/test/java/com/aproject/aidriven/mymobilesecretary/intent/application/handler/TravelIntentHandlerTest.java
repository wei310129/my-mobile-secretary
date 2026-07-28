package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.RestaurantBookingService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelItineraryDraftAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPackingAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPlanningIntakeService;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelConversationFocusBindingFactory;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelIntentHandlerTest {

    private TravelPlanningIntakeService planningService;
    private TravelPackingAnswerService packingService;
    private TravelItineraryDraftAnswerService itineraryService;
    private RestaurantBookingService restaurantService;
    private TravelConversationFocusBindingFactory focusBindings;
    private TravelIntentHandler handler;
    private IntentResult expected;

    @BeforeEach
    void setUp() {
        planningService = mock(TravelPlanningIntakeService.class);
        packingService = mock(TravelPackingAnswerService.class);
        itineraryService = mock(TravelItineraryDraftAnswerService.class);
        restaurantService = mock(RestaurantBookingService.class);
        focusBindings = mock(TravelConversationFocusBindingFactory.class);
        handler = new TravelIntentHandler(
                planningService, packingService, itineraryService, restaurantService,
                focusBindings);
        expected = IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "ok");
    }

    @Test
    void registersEveryTravelAndRestaurantType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.BOOK_RESTAURANT,
                IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT,
                IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT,
                IntentCommand.Type.LIST_PACKING_PREFERENCES,
                IntentCommand.Type.PLAN_PACKING_LIST,
                IntentCommand.Type.PLAN_TRIP,
                IntentCommand.Type.SET_PACKING_PREFERENCE,
                IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT));
    }

    @Test
    void delegatesEveryTravelCommandWithoutCallingLlm() {
        IntentCommand plan = command(IntentCommand.Type.PLAN_TRIP);
        IntentCommand packing = command(IntentCommand.Type.PLAN_PACKING_LIST);
        IntentCommand listPreferences = command(IntentCommand.Type.LIST_PACKING_PREFERENCES);
        IntentCommand setPreference = command(IntentCommand.Type.SET_PACKING_PREFERENCE);
        IntentCommand show = command(IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT);
        IntentCommand confirm = command(IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT);
        IntentCommand discard = command(IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT);
        IntentCommand restaurant = command(IntentCommand.Type.BOOK_RESTAURANT);
        when(planningService.intake("text")).thenReturn(expected);
        IntentResult packingResult = IntentResult.message(
                IntentResult.Action.PACKING_LIST_INFO, "packing");
        when(packingService.draft("text")).thenReturn(packingResult);
        ConversationFocusBinding planningBinding = ConversationFocusBinding.workflow(
                "TRAVEL", UUID.randomUUID(), "旅行規劃");
        ConversationFocusBinding packingBinding = ConversationFocusBinding.workflowActivity(
                "TRAVEL", planningBinding.workflowId(), "旅行規劃", "PACKING", "行李準備");
        when(focusBindings.planning()).thenReturn(planningBinding);
        when(focusBindings.packing()).thenReturn(packingBinding);
        when(packingService.listPreferences()).thenReturn(expected);
        when(packingService.setPreference("旅行用品", null, null)).thenReturn(expected);
        when(itineraryService.showLatest()).thenReturn(expected);
        when(itineraryService.confirmLatest()).thenReturn(expected);
        when(itineraryService.discardLatest()).thenReturn(expected);
        when(restaurantService.handle("text", restaurant)).thenReturn(expected);

        assertThat(handler.handle("text", plan).focusBinding()).isEqualTo(planningBinding);
        assertThat(handler.handle("text", packing).focusBinding()).isEqualTo(packingBinding);
        assertThat(handler.handle("text", listPreferences)).isSameAs(expected);
        assertThat(handler.handle("text", setPreference)).isSameAs(expected);
        assertThat(handler.handle("text", show)).isSameAs(expected);
        assertThat(handler.handle("text", confirm)).isSameAs(expected);
        assertThat(handler.handle("text", discard)).isSameAs(expected);
        assertThat(handler.handle("text", restaurant)).isSameAs(expected);
        verify(planningService).intake("text");
        verify(packingService).draft("text");
        verify(packingService).listPreferences();
        verify(packingService).setPreference("旅行用品", null, null);
        verify(itineraryService).showLatest();
        verify(itineraryService).confirmLatest();
        verify(itineraryService).discardLatest();
        verify(restaurantService).handle("text", restaurant);
    }

    @Test
    void missingActorLocalTripContextDoesNotCreatePackingFocus() {
        IntentCommand packing = command(IntentCommand.Type.PLAN_PACKING_LIST);
        when(packingService.draft("剛才那趟的行李"))
                .thenReturn(IntentResult.clarificationNeeded("請提供目的地"));

        IntentResult result = handler.handle("剛才那趟的行李", packing);

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.focusBinding()).isNull();
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, "旅行用品", null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
