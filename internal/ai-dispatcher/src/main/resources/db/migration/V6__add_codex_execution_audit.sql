CREATE TABLE codex_execution_attempt (
    external_execution_id   VARCHAR(200) PRIMARY KEY,
    run_id                  UUID NOT NULL UNIQUE
                                REFERENCES dispatcher_run (run_id) ON DELETE CASCADE,
    fencing_token           BIGINT NOT NULL,
    adapter_type            VARCHAR(30) NOT NULL,
    process_id              BIGINT,
    status                  VARCHAR(30) NOT NULL,
    started_at              TIMESTAMPTZ NOT NULL,
    last_progress_at        TIMESTAMPTZ NOT NULL,
    finished_at             TIMESTAMPTZ,
    terminal_event          VARCHAR(50),
    cli_exit_code           INTEGER,
    input_tokens            BIGINT NOT NULL DEFAULT 0,
    cached_input_tokens     BIGINT NOT NULL DEFAULT 0,
    output_tokens           BIGINT NOT NULL DEFAULT 0,
    reasoning_output_tokens BIGINT NOT NULL DEFAULT 0,
    diagnostic_code         VARCHAR(100),
    stderr_excerpt          TEXT,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_codex_execution_adapter
        CHECK (adapter_type IN ('CLI')),
    CONSTRAINT chk_codex_execution_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN')),
    CONSTRAINT chk_codex_execution_process_id
        CHECK (process_id IS NULL OR process_id > 0),
    CONSTRAINT chk_codex_execution_tokens
        CHECK (input_tokens >= 0 AND cached_input_tokens >= 0
            AND output_tokens >= 0 AND reasoning_output_tokens >= 0),
    CONSTRAINT chk_codex_execution_finish
        CHECK ((status = 'RUNNING' AND finished_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN')
                AND finished_at IS NOT NULL))
);

CREATE INDEX idx_codex_execution_status_progress
    ON codex_execution_attempt (status, last_progress_at);
