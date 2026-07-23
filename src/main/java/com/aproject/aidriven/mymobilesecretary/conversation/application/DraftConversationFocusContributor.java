package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates an actor-private pending itinerary draft before it becomes active focus. */
@Component
public final class DraftConversationFocusContributor implements ConversationFocusContributor {

    private static final String ITINERARY_PREFIX = "travel-itinerary-draft:";
    private final TravelItineraryDraftService drafts;

    public DraftConversationFocusContributor(TravelItineraryDraftService drafts) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
    }

    @Override
    public String rootDomain() {
        return "DRAFT";
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        Long id = TaskConversationFocusContributor.resourceId(target, ITINERARY_PREFIX);
        return id != null && drafts.isAvailableForFocus(id, target.safeLabel());
    }
}
