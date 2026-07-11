-- Phase 2A:物品知識庫。「排骨在全聯/菜市場買得到」這類事實,
-- 讓建任務時系統自動綁定可購買地點,取代使用者手動 bind。
CREATE TABLE item (
    id         BIGSERIAL PRIMARY KEY,
    -- 品項名,唯一;任務標題以「包含此字串」比對(Phase 3 换成 LLM 意圖理解)
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL
);

-- 品項可在哪些地點購買(多對多)
CREATE TABLE item_place (
    item_id  BIGINT NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    place_id BIGINT NOT NULL REFERENCES place (id),
    PRIMARY KEY (item_id, place_id)
);
