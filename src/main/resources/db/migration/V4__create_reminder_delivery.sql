-- Phase 1C:提醒送出紀錄。每個提醒 × 每個通知通道一筆,成功失敗都記,
-- 供「該響的那一次有沒有響」的可靠度追查(核心價值:可靠度 > 聰明度)。
CREATE TABLE reminder_delivery (
    id            BIGSERIAL PRIMARY KEY,
    reminder_id   BIGINT      NOT NULL REFERENCES reminder (id),
    -- LOG / WINDOWS_TOAST / APNS
    channel       VARCHAR(20) NOT NULL,
    success       BOOLEAN     NOT NULL,
    error_message VARCHAR(500),
    sent_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reminder_delivery_reminder ON reminder_delivery (reminder_id);
