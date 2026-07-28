ALTER TABLE calendar_plan
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN canceled_at TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ;

ALTER TABLE calendar_plan
    ADD CONSTRAINT chk_calendar_plan_lifecycle CHECK (
        (status = 'ACTIVE'
            AND canceled_at IS NULL
            AND archived_at IS NULL)
        OR (status = 'CANCELED'
            AND canceled_at IS NOT NULL
            AND archived_at IS NULL)
        OR (status = 'ARCHIVED'
            AND archived_at IS NOT NULL));

CREATE INDEX idx_calendar_plan_actor_status_updated
    ON calendar_plan (
        workspace_id, created_by_user_id, status, updated_at DESC);
