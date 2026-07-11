-- Phase 2B:行程表。後端 ScheduleItem 是行程的 source of truth
-- (EventKit 之後只是同步來源,見 architecture.md §16)。
CREATE TABLE schedule_item (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    start_at   TIMESTAMPTZ  NOT NULL,
    end_at     TIMESTAMPTZ  NOT NULL,
    -- 行程地點(選填:線上會議等無地點行程)
    place_id   BIGINT REFERENCES place (id),
    -- PROPOSED / CONFIRMED / PENDING / REJECTED / CANCELED / COMPLETED
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- 可行性引擎頻繁以「狀態 + 時間範圍」查前後行程與重疊
CREATE INDEX idx_schedule_item_status_start ON schedule_item (status, start_at);
