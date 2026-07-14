-- Phase 3:緩衝規則。依地點累積行程「計畫 vs 實際」差異(來源:schedule_outcome 回報),
-- 規劃引擎驗算時對該地點行程自動多留緩衝。Phase 4 的事件重播會接手自動歸納。
CREATE TABLE buffer_rule (
    id                    BIGSERIAL PRIMARY KEY,
    place_id              BIGINT      NOT NULL UNIQUE REFERENCES place (id),
    -- 累積統計:回報次數 / 準時次數 / 超時分鐘總和(準時計 0 分)
    sample_count          INT         NOT NULL,
    on_time_count         INT         NOT NULL,
    total_overrun_minutes BIGINT      NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);
