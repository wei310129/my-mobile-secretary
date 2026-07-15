package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentIssueRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 意圖問題 use case:記錄、查詢、標記處理狀態。
 */
@Service
@Transactional
public class IntentIssueService {

    private static final Logger log = LoggerFactory.getLogger(IntentIssueService.class);

    private final IntentIssueRepository repository;
    private final Clock clock;

    public IntentIssueService(IntentIssueRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 記錄一筆意圖問題。任何失敗只記 log——紀錄是輔助功能,
     * 不能因為它讓使用者的原始請求失敗。
     */
    public void recordSafely(String utterance, String botReply, IntentIssue.Category category) {
        try {
            repository.save(IntentIssue.open(utterance, botReply, category, Instant.now(clock)));
        } catch (Exception e) {
            log.warn("Intent issue logging failed [utterance={}]", utterance, e);
        }
    }

    /** 查清單;status 為 null 時列全部(新到舊)。 */
    @Transactional(readOnly = true)
    public List<IntentIssue> list(IntentIssue.Status status) {
        return status == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status);
    }

    /** 標記已解決。 */
    public IntentIssue resolve(Long issueId) {
        IntentIssue issue = get(issueId);
        issue.resolve(Instant.now(clock));
        return issue;
    }

    /** 標記超出服務範圍。 */
    public IntentIssue markOutOfScope(Long issueId) {
        IntentIssue issue = get(issueId);
        issue.markOutOfScope(Instant.now(clock));
        return issue;
    }

    private IntentIssue get(Long issueId) {
        return repository.findById(issueId)
                .orElseThrow(() -> new NotFoundException("IntentIssue", issueId));
    }
}
