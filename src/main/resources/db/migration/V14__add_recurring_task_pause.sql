ALTER TABLE task
    ADD COLUMN recurrence_paused BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_task_recurrence_paused_due
    ON task (recurrence, recurrence_paused, due_at);
