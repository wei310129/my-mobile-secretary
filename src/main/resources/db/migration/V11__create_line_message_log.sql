-- 對話紀錄(使用者 2026-07-15 要求):LINE 進出訊息逐筆留底,
-- 讓「看對話紀錄」成為可執行的請求(開發回顧/問題重現都靠這張表)。
CREATE TABLE line_message_log (
    id           BIGSERIAL PRIMARY KEY,
    -- IN(使用者傳來)/ OUT(bot 回覆)
    direction    VARCHAR(10)   NOT NULL,
    -- TEXT / IMAGE
    message_type VARCHAR(20)   NOT NULL,
    -- 文字內容;圖片存訊息 id 標記(截斷至 2000 字)
    content      VARCHAR(2000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL
);

-- 查最近對話的主路徑
CREATE INDEX idx_line_message_log_created ON line_message_log (created_at DESC);
