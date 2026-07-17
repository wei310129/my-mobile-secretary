package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LINE 對話紀錄 use case。
 *
 * 關鍵規則:留底失敗只記 log——紀錄是輔助功能,
 * 不能因為它讓訊息處理或回覆失敗(可靠度 > 完整度)。
 */
@Service
@Transactional
public class LineMessageLogService {

    private static final Logger log = LoggerFactory.getLogger(LineMessageLogService.class);
    private static final int MAX_LIMIT = 200;

    private final LineMessageLogRepository repository;
    private final LineMessageRetentionProperties properties;
    private final Clock clock;

    public LineMessageLogService(LineMessageLogRepository repository,
                                 LineMessageRetentionProperties properties,
                                 Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 記一筆進/出訊息;任何失敗都吞掉。 */
    public void recordSafely(LineMessageLog.Direction direction, String messageType, String content) {
        try {
            Instant now = Instant.now(clock);
            repository.save(LineMessageLog.of(direction, messageType, content,
                    now, now.plus(properties.retention())));
        } catch (Exception e) {
            log.warn("LINE message logging failed [direction={}]", direction, e);
        }
    }

    /** 最近的對話(新到舊);limit 夾在 1-200。 */
    @Transactional(readOnly = true)
    public List<LineMessageLog> listRecent(int limit) {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                scope.workspaceId(), scope.actorId(), PageRequest.of(0, clamped));
    }

    public LineMessageLog setPinned(long id, boolean pinned) {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        LineMessageLog entry = repository.findByIdAndWorkspaceIdAndCreatedByUserId(
                        id, scope.workspaceId(), scope.actorId())
                .orElseThrow(() -> new NotFoundException("LineMessageLog", id));
        entry.setPinned(pinned);
        return entry;
    }

    public void delete(long id) {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        LineMessageLog entry = repository.findByIdAndWorkspaceIdAndCreatedByUserId(
                        id, scope.workspaceId(), scope.actorId())
                .orElseThrow(() -> new NotFoundException("LineMessageLog", id));
        repository.delete(entry);
    }

    public long purgeExpired() {
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        return repository.deleteByWorkspaceIdAndPinnedFalseAndExpiresAtBefore(
                scope.workspaceId(), Instant.now(clock));
    }
}
