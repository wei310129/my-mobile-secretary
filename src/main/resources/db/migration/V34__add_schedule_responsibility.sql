ALTER TABLE schedule_item
    ADD COLUMN responsible_person VARCHAR(80),
    ADD COLUMN counts_for_actor_busy BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN end_time_explicit BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_schedule_item_actor_busy_time
    ON schedule_item (workspace_id, created_by_user_id, counts_for_actor_busy, start_at);
