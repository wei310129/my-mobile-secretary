package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.RestaurantBookingService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelItineraryDraftAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPackingAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPlanningIntakeService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelIntentHandlerTest {

    private TravelPlanningIntakeService planningService;
    private TravelPackingAnswerService packingService;
    private TravelItineraryDraftAnswerService itineraryService;
    private RestaurantBookingService restaurantService;
    private TravelIntentHandler handler;
    private IntentResult expected;

    @BeforeEach
    void setUp() {
        planningService = mock(TravelPlanningIntakeService.class);
        packingService = mock(TravelPackingAnswerService.class);
        itineraryService = mock(TravelItineraryDraftAnswerService.class);
        restaurantService = mock(RestaurantBookingService.class);
        handler = new TravelIntentHandler(
                planningService, packingService, itineraryService, restaurantService);
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
        when(packingService.draft("text")).thenReturn(expected);
        when(packingService.listPreferences()).thenReturn(expected);
        when(packingService.setPreference("旅行用品", null, null)).thenReturn(expected);
        when(itineraryService.showLatest()).thenReturn(expected);
        when(itineraryService.confirmLatest()).thenReturn(expected);
        when(itineraryService.discardLatest()).thenReturn(expected);
        when(restaurantService.handle("text", restaurant)).thenReturn(expected);

        assertThat(handler.handle("text", plan)).isSameAs(expected);
        assertThat(handler.handle("text", packing)).isSameAs(expected);
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

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, "旅行用品", null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
