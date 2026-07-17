ALTER TABLE dispatcher_run
    ADD COLUMN session_display_name_snapshot VARCHAR(200),
    ADD COLUMN session_provider_snapshot VARCHAR(50),
    ADD COLUMN external_session_id_snapshot VARCHAR(200),
    ADD COLUMN session_binding_version BIGINT;

UPDATE dispatcher_run r
SET session_display_name_snapshot = s.display_name,
    session_provider_snapshot = s.provider,
    external_session_id_snapshot = s.external_session_id,
    session_binding_version = s.version
FROM agent_session s
WHERE s.session_key = r.session_key
  AND s.external_session_id IS NOT NULL
  AND r.status IN ('STARTING', 'RUNNING', 'OUTCOME_UNKNOWN');

ALTER TABLE dispatcher_run
    ADD CONSTRAINT chk_dispatcher_run_session_snapshot
        CHECK ((session_display_name_snapshot IS NULL
                    AND session_provider_snapshot IS NULL
                    AND external_session_id_snapshot IS NULL
                    AND session_binding_version IS NULL)
            OR (session_display_name_snapshot IS NOT NULL
                    AND session_provider_snapshot IS NOT NULL
                    AND external_session_id_snapshot IS NOT NULL
                    AND session_binding_version IS NOT NULL
                    AND session_binding_version >= 0));
