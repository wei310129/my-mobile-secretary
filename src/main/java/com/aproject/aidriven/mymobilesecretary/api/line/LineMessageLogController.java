package com.aproject.aidriven.mymobilesecretary.api.line;

import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LINE 對話紀錄 API:「看對話紀錄」的落地——開發回顧與問題重現用。
 */
@RestController
@RequestMapping("/api/line/messages")
public class LineMessageLogController {

    private final LineMessageLogService messageLogService;

    public LineMessageLogController(LineMessageLogService messageLogService) {
        this.messageLogService = messageLogService;
    }

    /** 最近的對話(新到舊;?limit=50,上限 200)。 */
    @GetMapping
    public List<MessageLogResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return messageLogService.listRecent(limit).stream().map(MessageLogResponse::from).toList();
    }

    @PatchMapping("/{messageId}/pin")
    public MessageLogResponse pin(@PathVariable long messageId, @RequestBody PinRequest request) {
        return MessageLogResponse.from(messageLogService.setPinned(messageId, request.pinned()));
    }

    @DeleteMapping("/{messageId}")
    public void delete(@PathVariable long messageId) {
        messageLogService.delete(messageId);
    }

    public record PinRequest(boolean pinned) {
    }

    /** 對話紀錄的 API 回應。 */
    public record MessageLogResponse(
            Long id,
            LineMessageLog.Direction direction,
            String messageType,
            String content,
            Instant createdAt,
            boolean pinned,
            Instant expiresAt
    ) {

        static MessageLogResponse from(LineMessageLog entry) {
            return new MessageLogResponse(entry.getId(), entry.getDirection(),
                    entry.getMessageType(), entry.getContent(), entry.getCreatedAt(),
                    entry.isPinned(), entry.getExpiresAt());
        }
    }
}
