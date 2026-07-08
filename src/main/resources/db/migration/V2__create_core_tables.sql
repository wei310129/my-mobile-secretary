-- Phase 1A 核心資料表:任務、地點、geofence 規則、提醒。
-- 時間欄位一律 TIMESTAMPTZ(對應 Java Instant),避免時區歧義。

CREATE TABLE task (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    -- 狀態機:CREATED/SCHEDULED/REMINDED/ESCALATED/CONFIRMED/CANCELED,轉換規則由 domain 控管
    status      VARCHAR(20)  NOT NULL,
    priority    VARCHAR(10)  NOT NULL,
    due_at      TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE place (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    address    VARCHAR(300),
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    -- 地點類型先用自由文字,真實使用一段時間後再收斂成 enum(決策紀錄見 development-plan)
    type       VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE geofence_rule (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES task (id),
    place_id      BIGINT      NOT NULL REFERENCES place (id),
    radius_meters INT         NOT NULL,
    -- ENTER / EXIT
    trigger_type  VARCHAR(10) NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL
);

-- Phase 1B 的 geofence 比對會以 task/place 查詢規則
CREATE INDEX idx_geofence_rule_task ON geofence_rule (task_id);
CREATE INDEX idx_geofence_rule_place ON geofence_rule (place_id);

CREATE TABLE reminder (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT      NOT NULL REFERENCES task (id),
    status         VARCHAR(20) NOT NULL,
    triggered_at   TIMESTAMPTZ,
    confirmed_at   TIMESTAMPTZ,
    trigger_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reminder_task ON reminder (task_id);
