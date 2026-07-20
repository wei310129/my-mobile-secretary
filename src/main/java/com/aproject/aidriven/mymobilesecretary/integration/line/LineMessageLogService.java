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
        recordSafely(direction, messageType, content, null, null);
    }

    /** Records LINE ids needed to resolve quoted messages; failures remain non-fatal. */
    public void recordSafely(LineMessageLog.Direction direction, String messageType, String content,
                             String externalMessageId, String quotedMessageId) {
        try {
            Instant now = Instant.now(clock);
            repository.save(LineMessageLog.of(direction, messageType, content,
                    externalMessageId, quotedMessageId,
                    now, now.plus(properties.retention())));
        } catch (Exception e) {
            log.warn("LINE message logging failed [direction={}]", direction, e);
        }
    }

    /** Keeps a bounded safe summary on the original image so LINE quote replies retain meaning. */
    public void enrichImageContextSafely(String externalMessageId, String summary) {
        if (externalMessageId == null || externalMessageId.isBlank()
                || summary == null || summary.isBlank()) return;
        try {
            WorkspaceContext scope = WorkspaceContextHolder.requireContext();
            repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                            scope.workspaceId(), scope.actorId(), externalMessageId)
                    .ifPresent(entry -> entry.enrichImageContext(summary));
        } catch (RuntimeException failure) {
            log.warn("LINE image context enrichment failed ({})",
                    failure.getClass().getSimpleName());
        }
    }

    /** Builds bounded interpreter-only context; the original user text remains the audit record. */
    @Transactional(readOnly = true)
    public String contextualize(String text, String quotedMessageId) {
        String original = text == null ? "" : text.strip();
        if (quotedMessageId == null && !looksElliptical(original)) return original;
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        LineMessageLog quotedEntry = quotedMessageId == null ? null
                : repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                                scope.workspaceId(), scope.actorId(), quotedMessageId)
                        .orElse(null);
        String quoted = quotedEntry == null ? null : quotedEntry.getContent();
        if (quotedEntry != null && "IMAGE".equals(quotedEntry.getMessageType())
                && !quoted.startsWith("[圖片解析結果]")) {
            quoted = repository
                    .findFirstByWorkspaceIdAndCreatedByUserIdAndDirectionAndIdGreaterThanOrderByIdAsc(
                            scope.workspaceId(), scope.actorId(), LineMessageLog.Direction.OUT,
                            quotedEntry.getId())
                    .map(entry -> "[圖片解析結果]\n" + entry.getContent())
                    .orElse(quoted);
        }
        List<LineMessageLog> recent = listRecent(6);
        String recentText = recent.stream()
                .sorted(java.util.Comparator.comparing(LineMessageLog::getCreatedAt))
                .map(entry -> (entry.getDirection() == LineMessageLog.Direction.IN ? "使用者：" : "助理：")
                        + truncateContext(entry.getContent()))
                .collect(java.util.stream.Collectors.joining("\n"));
        StringBuilder context = new StringBuilder();
        if (quoted != null) context.append("【LINE 明確引用】").append(truncateContext(quoted)).append('\n');
        if (!recentText.isBlank()) context.append("【近期對話】\n").append(recentText).append('\n');
        return context.append("【使用者目前訊息】").append(original).toString();
    }

    private static boolean looksElliptical(String text) {
        return text.length() <= 30 || text.matches("\\d{1,2}[/-]\\d{1,2}")
                || text.matches("(?i)(好|要|不用|不用了|可以|對|是|不是|這個|那個)");
    }

    private static String truncateContext(String text) {
        if (text == null) return "";
        String value = text.strip();
        return value.length() <= 500 ? value : value.substring(0, 500);
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
