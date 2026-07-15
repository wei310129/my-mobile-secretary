-- Phase 3 收尾:意圖問題紀錄。bot 聽不懂/退回保底的話語逐筆入庫,
-- 開發前檢視 OPEN 項目針對性補強;解決標 RESOLVED,超出服務範圍標 OUT_OF_SCOPE。
CREATE TABLE intent_issue (
    id         BIGSERIAL PRIMARY KEY,
    -- 使用者原話(截斷至 500 字)
    utterance  VARCHAR(500) NOT NULL,
    -- 當下 bot 的回覆(還原情境用)
    bot_reply  VARCHAR(500),
    -- CLARIFICATION(回問)/ FALLBACK(LLM 失敗退回保底任務)
    category   VARCHAR(30)  NOT NULL,
    -- OPEN / RESOLVED / OUT_OF_SCOPE
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- 開發前查 OPEN 清單的主路徑
CREATE INDEX idx_intent_issue_status ON intent_issue (status, created_at DESC);
