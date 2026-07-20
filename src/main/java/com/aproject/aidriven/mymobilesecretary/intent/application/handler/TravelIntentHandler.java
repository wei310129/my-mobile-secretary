package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.RestaurantBookingService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelItineraryDraftAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPackingAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.TravelPlanningIntakeService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Handles deterministic travel planning, packing, itinerary draft and restaurant guidance. */
@Component
@RequiredArgsConstructor
public final class TravelIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.BOOK_RESTAURANT,
            IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT,
            IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT,
            IntentCommand.Type.LIST_PACKING_PREFERENCES,
            IntentCommand.Type.PLAN_PACKING_LIST,
            IntentCommand.Type.PLAN_TRIP,
            IntentCommand.Type.SET_PACKING_PREFERENCE,
            IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT);

    private final TravelPlanningIntakeService planningService;
    private final TravelPackingAnswerService packingService;
    private final TravelItineraryDraftAnswerService itineraryDraftService;
    private final RestaurantBookingService restaurantBookingService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case PLAN_TRIP -> planningService.intake(text);
            case PLAN_PACKING_LIST -> packingService.draft(text);
            case LIST_PACKING_PREFERENCES -> packingService.listPreferences();
            case SET_PACKING_PREFERENCE -> {
                requireText(command.title(), "title");
                yield packingService.setPreference(
                        command.title(), command.safeOptions().filter(), command.reason());
            }
            case SHOW_TRAVEL_ITINERARY_DRAFT -> itineraryDraftService.showLatest();
            case CONFIRM_TRAVEL_ITINERARY_DRAFT -> itineraryDraftService.confirmLatest();
            case DISCARD_TRAVEL_ITINERARY_DRAFT -> itineraryDraftService.discardLatest();
            case BOOK_RESTAURANT -> restaurantBookingService.handle(text, command);
            default -> throw new IllegalArgumentException(
                    "unsupported travel intent type " + command.type());
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " missing");
        }
    }
}
