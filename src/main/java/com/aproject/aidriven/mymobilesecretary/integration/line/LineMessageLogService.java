package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
        recordSafely(direction, messageType, content, externalMessageId, quotedMessageId, null);
    }

    public void recordSafely(LineMessageLog.Direction direction, String messageType, String content,
                             String externalMessageId, String quotedMessageId,
                             String referencePayload) {
        try {
            Instant now = Instant.now(clock);
            LineMessageLog entry = LineMessageLog.of(direction, messageType, content,
                    externalMessageId, quotedMessageId,
                    now, now.plus(properties.retention()));
            entry.attachReferences(referencePayload);
            repository.save(entry);
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

    public void attachReferencesSafely(String externalMessageId, String referencePayload) {
        try {
            WorkspaceContext scope = WorkspaceContextHolder.requireContext();
            repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                            scope.workspaceId(), scope.actorId(), externalMessageId)
                    .ifPresent(entry -> {
                        entry.attachReferences(referencePayload);
                        repository.save(entry);
                    });
        } catch (RuntimeException failure) {
            log.warn(
                    "LINE reference attachment failed ({})",
                    failure.getClass().getSimpleName());
        }
    }

    /** Resolves safe interpreter text and keeps recognized typed references out of the prompt. */
    @Transactional(readOnly = true)
    public ResolvedConversationContext resolveContext(
            String text, String quotedMessageId) {
        String original = text == null ? "" : text.strip();
        if (quotedMessageId == null && !needsConversationContext(original)) {
            return new ResolvedConversationContext(original, null, null);
        }
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        LineMessageLog quotedEntry = quotedMessageId == null ? null
                : repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                                scope.workspaceId(), scope.actorId(), quotedMessageId)
                        .orElse(null);
        String quoted = quotedEntry == null ? null : quotedEntry.getContent();
        if (quotedEntry != null && "IMAGE".equals(quotedEntry.getMessageType())
                && quoted != null && !quoted.startsWith("[image interpretation]")) {
            quoted = repository
                    .findFirstByWorkspaceIdAndCreatedByUserIdAndDirectionAndIdGreaterThanOrderByIdAsc(
                            scope.workspaceId(), scope.actorId(), LineMessageLog.Direction.OUT,
                            quotedEntry.getId())
                    .map(entry -> "[image interpretation]\n" + entry.getContent())
                    .orElse(quoted);
        }
        StringBuilder context = new StringBuilder();
        UUID trustedMaterializationProposalId = null;
        Long trustedMediaId = null;
        if (quoted != null) {
            context.append("LINE quoted message:\n")
                    .append(truncateContext(quoted))
                    .append('\n');
            if (quotedEntry.getReferencePayload() != null) {
                String referencePayload = quotedEntry.getReferencePayload();
                trustedMaterializationProposalId =
                        trustedMaterializationProposalId(referencePayload);
                trustedMediaId = trustedMediaId(referencePayload);
                String interpreterReferences = interpreterReferencePayload(referencePayload);
                if (!interpreterReferences.isBlank()) {
                    context.append("LINE quoted references:\n")
                            .append(interpreterReferences)
                            .append('\n');
                }
            }
        } else {
            List<LineMessageLog> recent = listRecent(6);
            String recentText = recent.stream()
                    .sorted(java.util.Comparator.comparing(LineMessageLog::getCreatedAt))
                    .map(entry -> (entry.getDirection() == LineMessageLog.Direction.IN
                            ? "user: " : "assistant: ") + truncateContext(entry.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!recentText.isBlank()) {
                context.append("Recent conversation:\n").append(recentText).append('\n');
            }
        }
        return new ResolvedConversationContext(
                context.append("Current message:\n").append(original).toString(),
                trustedMaterializationProposalId,
                trustedMediaId);
    }

    private static UUID trustedMaterializationProposalId(String payload) {
        UUID found = null;
        for (String part : payload.split(";")) {
            if (!part.startsWith("MATERIALIZATION:")) {
                continue;
            }
            String[] fields = part.split(":", -1);
            if (fields.length != 3 || !"1".equals(fields[2]) || found != null) {
                return null;
            }
            try {
                found = UUID.fromString(fields[1]);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
        return found;
    }

    private static String interpreterReferencePayload(String payload) {
        return java.util.Arrays.stream(payload.split(";"))
                .filter(part -> !part.startsWith("MATERIALIZATION:")
                        && !part.startsWith("MEDIA:"))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Long trustedMediaId(String payload) {
        Long found = null;
        for (String part : payload.split(";")) {
            if (!part.startsWith("MEDIA:")) {
                continue;
            }
            String[] fields = part.split(":", -1);
            if (fields.length != 3 || !"1".equals(fields[2]) || found != null) {
                return null;
            }
            try {
                found = Long.valueOf(fields[1]);
                if (found <= 0) {
                    return null;
                }
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return found;
    }

    public record ResolvedConversationContext(
            String interpreterText,
            UUID trustedMaterializationProposalId,
            Long trustedMediaId) {}

    /** Builds bounded interpreter-only context; the original user text remains the audit record. */
    @Transactional(readOnly = true)
    public String contextualize(String text, String quotedMessageId) {
        String original = text == null ? "" : text.strip();
        if (quotedMessageId == null && !needsConversationContext(original)) return original;
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
        StringBuilder context = new StringBuilder();
        if (quoted != null) {
            // A resolved LINE quote is the highest-signal context. Do not also attach the
            // recent transcript: it is redundant, may introduce another topic, and increases
            // every structured-output request without helping the reference resolution.
            context.append("【LINE 明確引用】").append(truncateContext(quoted)).append('\n');
            if (quotedEntry.getReferencePayload() != null) {
                context.append("【LINE 引用參考】")
                        .append(quotedEntry.getReferencePayload()).append('\n');
            }
        } else {
            List<LineMessageLog> recent = listRecent(6);
            String recentText = recent.stream()
                    .sorted(java.util.Comparator.comparing(LineMessageLog::getCreatedAt))
                    .map(entry -> (entry.getDirection() == LineMessageLog.Direction.IN
                            ? "使用者：" : "助理：") + truncateContext(entry.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!recentText.isBlank()) context.append("【近期對話】\n").append(recentText).append('\n');
        }
        return context.append("【使用者目前訊息】").append(original).toString();
    }

    private static boolean looksElliptical(String text) {
        return text.length() <= 30 || text.matches("\\d{1,2}[/-]\\d{1,2}")
                || text.matches("(?i)(好|要|不用|不用了|可以|對|是|不是|這個|那個)");
    }

    /** 課程名稱是語音轉錄常見的同音字位置，長句也保留近期紀錄供唯一比對。 */
    private static boolean needsConversationContext(String text) {
        if (looksElliptical(text)) return true;
        String compact = text.replaceAll("\\s+", "");
        boolean child = compact.contains("女兒") || compact.contains("兒子")
                || compact.contains("孩子") || compact.contains("小孩");
        boolean course = compact.contains("課") || compact.contains("補習")
                || compact.contains("安親") || compact.contains("英文") || compact.contains("才藝");
        return child && course;
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
