-- Phase 1B:位置事件表。手機(Phase 1 為 API 模擬)回報的離散事件,
-- 依隱私原則只存進入/離開/手動回報,絕不存連續軌跡。
CREATE TABLE location_event (
    id          BIGSERIAL PRIMARY KEY,
    -- ENTER / EXIT / MANUAL_PING
    event_type  VARCHAR(15) NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    -- 事件在裝置上發生的時間(客戶端回報)
    occurred_at TIMESTAMPTZ NOT NULL,
    -- 事件來源,例如 api-simulated / ios
    source      VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_location_event_occurred ON location_event (occurred_at);
