package com.aproject.aidriven.mymobilesecretary.integration.line;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * LINE 對話紀錄:一筆 = 一則進(使用者)或出(bot)的訊息。
 * 純留底用,不參與任何業務判斷;單人系統,無隱私共享議題。
 */
@Entity
public class LineMessageLog extends WorkspaceOwnedEntity {

    /** 訊息方向。 */
    public enum Direction {
        /** 使用者傳來。 */
        IN,
        /** bot 回覆。 */
        OUT
    }

    private static final int MAX_CONTENT = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(nullable = false, length = 20)
    private String messageType;

    @Column(nullable = false, length = MAX_CONTENT)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private Instant expiresAt;

    /** JPA 專用。 */
    protected LineMessageLog() {
    }

    private LineMessageLog(Direction direction, String messageType, String content,
                           Instant now, Instant expiresAt) {
        this.direction = direction;
        this.messageType = messageType;
        this.content = truncate(content);
        this.createdAt = now;
        this.pinned = false;
        this.expiresAt = expiresAt;
    }

    /** 記一筆訊息;內容截斷防爆欄位。 */
    public static LineMessageLog of(Direction direction, String messageType, String content, Instant now) {
        return of(direction, messageType, content, now, now.plus(java.time.Duration.ofDays(90)));
    }

    public static LineMessageLog of(Direction direction, String messageType, String content,
                                    Instant now, Instant expiresAt) {
        return new LineMessageLog(direction, messageType,
                content == null || content.isBlank() ? "(空)" : content, now, expiresAt);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT);
    }

    public Long getId() {
        return id;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }
}
