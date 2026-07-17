package com.aproject.aidriven.mymobilesecretary.integration.developmentfeed;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogRepository;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(
        prefix = "app.integration.development-feed", name = "enabled", havingValue = "true")
public class DevelopmentFeedService {

    private final LineMessageLogRepository repository;
    private final DevelopmentFeedProperties properties;
    private final DevelopmentFeedCursorCodec cursorCodec;

    public DevelopmentFeedService(LineMessageLogRepository repository,
                                  DevelopmentFeedProperties properties,
                                  DevelopmentFeedCursorCodec cursorCodec) {
        this.repository = repository;
        this.properties = properties;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public DevelopmentFeedPage readAfter(String cursor, int requestedLimit) {
        requireIntegrationScope();
        if (requestedLimit <= 0 || requestedLimit > properties.maxPageSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + properties.maxPageSize());
        }
        long afterId = cursorCodec.decode(cursor);
        List<LineMessageLog> rows = repository
                .findAllByDirectionAndWorkspaceIdAndCreatedByUserIdAndIdGreaterThanOrderByIdAsc(
                        LineMessageLog.Direction.IN,
                        properties.workspaceId(), properties.actorId(), afterId,
                        PageRequest.of(0, requestedLimit + 1));
        boolean hasMore = rows.size() > requestedLimit;
        List<LineMessageLog> pageRows = hasMore ? rows.subList(0, requestedLimit) : rows;
        List<DevelopmentFeedPage.Event> events = pageRows.stream().map(this::toEvent).toList();
        long nextId = pageRows.isEmpty() ? afterId : pageRows.getLast().getId();
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

    private DevelopmentFeedPage.Event toEvent(LineMessageLog row) {
        String reference = "line-message:" + row.getId();
        return new DevelopmentFeedPage.Event(
                reference,
                "line.conversation.recorded",
                row.getCreatedAt(),
                reference,
                1,
                Map.of("messageType", row.getMessageType(), "text", row.getContent()));
    }
}
