package com.aproject.aidriven.mymobilesecretary.intent.domain;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 意圖問題:bot 聽不懂或退回保底的一句話,等開發者處理的待辦。
 *
 * 使用者 2026-07-15 指示的工作流:每次開發前檢視 OPEN 項目,
 * 補強後標 RESOLVED;超出服務範圍(如要求寫程式)標 OUT_OF_SCOPE。
 */
@Entity
public class IntentIssue {

    /** 問題來源分類。 */
    public enum Category {
        /** bot 回問(聽不懂、資訊不足、配對模糊)。 */
        CLARIFICATION,
        /** LLM 失敗退回保底任務。 */
        FALLBACK,
        /** 使用者對系統本身的抱怨/建議(「你是不是重複建立了」)。 */
        FEEDBACK
    }

    /** 處理狀態:OPEN 是唯一非終止態。 */
    public enum Status {
        OPEN, RESOLVED, OUT_OF_SCOPE
    }

    private static final int MAX_TEXT = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_TEXT)
    private String utterance;

    @Column(length = MAX_TEXT)
    private String botReply;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** JPA 專用。 */
    protected IntentIssue() {
    }

    private IntentIssue(String utterance, String botReply, Category category, Instant now) {
        this.utterance = truncate(utterance);
        this.botReply = truncate(botReply);
        this.category = category;
        this.status = Status.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 開一筆待處理的意圖問題;文字截斷防爆欄位。 */
    public static IntentIssue open(String utterance, String botReply, Category category, Instant now) {
        return new IntentIssue(utterance, botReply, category, now);
    }

    /** 已補強,結案。 */
    public void resolve(Instant now) {
        transitionTo(Status.RESOLVED, now);
    }

    /** 超出服務範圍,不處理。 */
    public void markOutOfScope(Instant now) {
        transitionTo(Status.OUT_OF_SCOPE, now);
    }

    private void transitionTo(Status target, Instant now) {
        if (status != Status.OPEN) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "IntentIssue %d cannot transition from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    public Long getId() {
        return id;
    }

    public String getUtterance() {
        return utterance;
    }

    public String getBotReply() {
        return botReply;
    }

    public Category getCategory() {
        return category;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
