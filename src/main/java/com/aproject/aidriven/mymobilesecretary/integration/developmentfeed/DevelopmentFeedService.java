package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentIssueRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(
        prefix = "app.integration.development-feed", name = "enabled", havingValue = "true")
public class DevelopmentFeedService {

    private static final int EVENT_ENVELOPE_BYTES = 256;

    private final IntentIssueRepository repository;
    private final DevelopmentFeedProperties properties;
    private final DevelopmentFeedCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;

    public DevelopmentFeedService(IntentIssueRepository repository,
                                  DevelopmentFeedProperties properties,
                                  DevelopmentFeedCursorCodec cursorCodec,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DevelopmentFeedPage readAfter(String cursor, int requestedLimit) {
        requireIntegrationScope();
        if (requestedLimit <= 0 || requestedLimit > properties.maxPageSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + properties.maxPageSize());
        }
        long afterId = cursorCodec.decode(cursor);
        List<IntentIssue> candidates = repository
                .findAllByWorkspaceIdAndCreatedByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
                        properties.workspaceId(), properties.actorId(), IntentIssue.Status.OPEN,
                        afterId, PageRequest.of(0, requestedLimit + 1));
        List<DevelopmentFeedPage.Event> events = new ArrayList<>();
        int payloadBytes = 0;
        for (IntentIssue issue : candidates.stream().limit(requestedLimit).toList()) {
            DevelopmentFeedPage.Event event = toEvent(issue);
            int eventBytes = serializedBytes(event.metadata()) + EVENT_ENVELOPE_BYTES;
            if (!events.isEmpty() && payloadBytes + eventBytes > properties.maxPayloadBytes()) {
                break;
            }
            events.add(event);
            payloadBytes += eventBytes;
        }
        boolean hasMore = candidates.size() > events.size();
        long nextId = events.isEmpty() ? afterId
                : Long.parseLong(events.getLast().eventId().substring("intent-issue:".length()));
        return new DevelopmentFeedPage(events, cursorCodec.encode(nextId), hasMore);
    }

    private void requireIntegrationScope() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (context.channel() != WorkspaceChannel.INTEGRATION
                || !context.workspaceId().equals(properties.workspaceId())
                || !context.actorId().equals(properties.actorId())) {
            throw new SecurityException("Development feed requires its configured integration scope");
        }
    }

    private DevelopmentFeedPage.Event toEvent(IntentIssue issue) {
        String reference = "intent-issue:" + issue.getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", issue.getCategory().name());
        metadata.put("utterance", issue.getUtterance());
        if (issue.getBotReply() != null) {
            metadata.put("botReply", issue.getBotReply());
        }
        return new DevelopmentFeedPage.Event(
                reference,
                "intent.issue.opened",
                issue.getCreatedAt(),
                reference,
                2,
                metadata);
    }

    private int serializedBytes(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsBytes(metadata).length;
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Development issue metadata could not be serialized", serializationFailure);
        }
    }
}
