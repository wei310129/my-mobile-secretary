ALTER TABLE line_message_log
    ADD COLUMN external_message_id VARCHAR(100),
    ADD COLUMN quoted_message_id   VARCHAR(100);

CREATE INDEX idx_line_message_log_external_message
    ON line_message_log (workspace_id, created_by_user_id, external_message_id)
    WHERE external_message_id IS NOT NULL;
