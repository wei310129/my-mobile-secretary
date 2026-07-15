package com.aproject.aidriven.mymobilesecretary.integration.line;

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
    private final Clock clock;

    public LineMessageLogService(LineMessageLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 記一筆進/出訊息;任何失敗都吞掉。 */
    public void recordSafely(LineMessageLog.Direction direction, String messageType, String content) {
        try {
            repository.save(LineMessageLog.of(direction, messageType, content, Instant.now(clock)));
        } catch (Exception e) {
            log.warn("LINE message logging failed [direction={}]", direction, e);
        }
    }

    /** 最近的對話(新到舊);limit 夾在 1-200。 */
    @Transactional(readOnly = true)
    public List<LineMessageLog> listRecent(int limit) {
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, clamped));
    }
}
