package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.SemanticTagEdgeRepository;
import com.aproject.aidriven.mymobilesecretary.venue.application.VenueVisitInformationService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates supported knowledge resources under the current actor/workspace RLS context. */
@Component
public final class KnowledgeConversationFocusContributor implements ConversationFocusContributor {

    private static final String VENUE_PREFIX = "venue-visit-info:";
    private static final String TAG_EDGE_PREFIX = "tag-edge:";
    private final VenueVisitInformationService venueInformation;
    private final SemanticTagEdgeRepository tagEdges;

    public KnowledgeConversationFocusContributor(
            VenueVisitInformationService venueInformation,
            SemanticTagEdgeRepository tagEdges) {
        this.venueInformation = Objects.requireNonNull(venueInformation, "venueInformation");
        this.tagEdges = Objects.requireNonNull(tagEdges, "tagEdges");
    }

    @Override
    public String rootDomain() {
        return "KNOWLEDGE";
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        Long id = TaskConversationFocusContributor.resourceId(target, VENUE_PREFIX);
        if (id != null) {
            return venueInformation.isAvailableForFocus(id, target.safeLabel());
        }
        Long edgeId = TaskConversationFocusContributor.resourceId(target, TAG_EDGE_PREFIX);
        return edgeId != null && tagEdges.existsById(edgeId);
    }
}
