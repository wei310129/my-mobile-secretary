-- 生活化對話能力的共同資料底座。
-- 系統在 Phase 5 前維持單人設計，因此 conversation_context / planning_preference
-- 使用固定 id=1，不提前加入 user_id。

ALTER TABLE task
    ADD COLUMN category          VARCHAR(20)  NOT NULL DEFAULT 'OTHER',
    ADD COLUMN recurrence        VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN condition_type    VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN condition_payload VARCHAR(1000);

ALTER TABLE item
    ADD COLUMN inventory_quantity INT         NOT NULL DEFAULT 0,
    ADD COLUMN shopping_needed    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN updated_at         TIMESTAMPTZ;

UPDATE item SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE item ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX idx_task_category_due ON task (category, due_at);
CREATE INDEX idx_task_recurrence_due ON task (recurrence, due_at);
CREATE INDEX idx_item_shopping_needed ON item (shopping_needed, name);

CREATE TABLE conversation_context (
    id                     INT PRIMARY KEY,
    last_task_id           BIGINT,
    last_schedule_id       BIGINT,
    last_place_id          BIGINT,
    last_task_list_ids     VARCHAR(2000),
    last_schedule_list_ids VARCHAR(2000),
    last_action            VARCHAR(60),
    last_user_text         VARCHAR(500),
    last_assistant_text    VARCHAR(2000),
    updated_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE place_alias (
    id         BIGSERIAL PRIMARY KEY,
    alias      VARCHAR(100) NOT NULL UNIQUE,
    place_id   BIGINT       NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_place_alias_place ON place_alias (place_id);

CREATE TABLE planning_preference (
    id                      INT PRIMARY KEY,
    extra_transfer_minutes  INT         NOT NULL DEFAULT 0,
    meal_buffer_minutes     INT         NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL
);
