-- Phase 3:行程結果追蹤閉環。
-- schedule_follow_up:「該問了嗎/問了沒/答了沒」的排程狀態(每行程最多一筆)。
-- schedule_outcome:使用者回報的實際結果(計畫 vs 實際),之後供緩衝規則累積。
CREATE TABLE schedule_follow_up (
    id               BIGSERIAL PRIMARY KEY,
    schedule_item_id BIGINT      NOT NULL UNIQUE REFERENCES schedule_item (id),
    -- 何時發問:行程結束 +15 分鐘,或 GPS 離開行程地點 +5 分鐘(先到先發)
    due_at           TIMESTAMPTZ NOT NULL,
    -- SCHEDULED / ASKED / ANSWERED
    status           VARCHAR(20) NOT NULL,
    asked_at         TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- worker 以「狀態 + 到期時間」輪詢待發問項目
CREATE INDEX idx_schedule_follow_up_status_due ON schedule_follow_up (status, due_at);

CREATE TABLE schedule_outcome (
    id               BIGSERIAL PRIMARY KEY,
    schedule_item_id BIGINT      NOT NULL UNIQUE REFERENCES schedule_item (id),
    on_time          BOOLEAN     NOT NULL,
    -- 超時分鐘數;準時為 NULL
    overrun_minutes  INT,
    -- MEETING_OVERRUN / TRAFFIC_INCIDENT / RUSH_HOUR / OTHER;準時為 NULL
    reason           VARCHAR(30),
    note             VARCHAR(500),
    recorded_at      TIMESTAMPTZ NOT NULL
);
