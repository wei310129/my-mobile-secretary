INSERT INTO trigger_cursor (source_key, cursor_value, version, created_at, updated_at)
VALUES ('main-development-issue-feed-v2', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (source_key) DO NOTHING;
