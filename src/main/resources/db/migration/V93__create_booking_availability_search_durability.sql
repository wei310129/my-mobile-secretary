CREATE TABLE booking_search_job (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    terminal_fingerprint CHAR(64),
    terminal_at TIMESTAMP WITH TIME ZONE,
    retention_until TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_search_job_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_search_job_idempotency
        UNIQUE (workspace_id, created_by_user_id, idempotency_key),
    CONSTRAINT ck_booking_search_job_status
        CHECK (status IN ('STARTED', 'COMPLETE', 'PARTIAL', 'NO_RESULTS', 'FAILED')),
    CONSTRAINT ck_booking_search_job_terminal
        CHECK (
            (status = 'STARTED'
                AND terminal_fingerprint IS NULL
                AND terminal_at IS NULL)
            OR
            (status <> 'STARTED'
                AND terminal_fingerprint IS NOT NULL
                AND terminal_at IS NOT NULL)),
    CONSTRAINT ck_booking_search_job_retention
        CHECK (retention_until > created_at)
);

CREATE TABLE booking_search_candidate (
    id UUID PRIMARY KEY,
    search_job_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    source_key VARCHAR(120) NOT NULL,
    inventory_identity VARCHAR(256) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available BOOLEAN NOT NULL,
    feasibility VARCHAR(24) NOT NULL,
    displayed_price NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    total_fully_known BOOLEAN NOT NULL,
    required_add_on_count INTEGER NOT NULL,
    refund_risk VARCHAR(16) NOT NULL,
    change_risk VARCHAR(16) NOT NULL,
    restriction_count INTEGER NOT NULL,
    recommendation_badges VARCHAR(160) NOT NULL DEFAULT '',
    offer_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_search_candidate_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_search_candidate_identity
        UNIQUE (
            search_job_id, candidate_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_search_candidate_job_owner
        FOREIGN KEY (search_job_id, workspace_id, created_by_user_id)
        REFERENCES booking_search_job (id, workspace_id, created_by_user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_booking_search_candidate_offer_owner
        FOREIGN KEY (offer_id, workspace_id, created_by_user_id)
        REFERENCES booking_offer_snapshot (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_search_candidate_feasibility
        CHECK (feasibility IN ('FEASIBLE', 'UNKNOWN', 'IMPOSSIBLE')),
    CONSTRAINT ck_booking_search_candidate_price
        CHECK (displayed_price >= 0),
    CONSTRAINT ck_booking_search_candidate_counts
        CHECK (
            required_add_on_count >= 0
            AND restriction_count >= 0),
    CONSTRAINT ck_booking_search_candidate_refund_risk
        CHECK (refund_risk IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    CONSTRAINT ck_booking_search_candidate_change_risk
        CHECK (change_risk IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'))
);

CREATE TABLE booking_search_progress (
    id UUID PRIMARY KEY,
    search_job_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    stage VARCHAR(24) NOT NULL,
    source_key VARCHAR(120),
    source_status VARCHAR(24),
    candidate_count INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_search_progress_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_search_progress_sequence
        UNIQUE (
            search_job_id, sequence, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_search_progress_job_owner
        FOREIGN KEY (search_job_id, workspace_id, created_by_user_id)
        REFERENCES booking_search_job (id, workspace_id, created_by_user_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_booking_search_progress_sequence
        CHECK (sequence >= 0),
    CONSTRAINT ck_booking_search_progress_stage
        CHECK (stage IN ('STARTED', 'SOURCE_COMPLETED', 'TERMINAL')),
    CONSTRAINT ck_booking_search_progress_source
        CHECK (
            (stage = 'SOURCE_COMPLETED'
                AND source_key IS NOT NULL
                AND source_status IS NOT NULL)
            OR
            (stage <> 'SOURCE_COMPLETED'
                AND source_key IS NULL
                AND source_status IS NULL
                AND candidate_count IS NULL)),
    CONSTRAINT ck_booking_search_progress_source_status
        CHECK (
            source_status IS NULL
            OR source_status IN ('SUCCESS', 'FAILED', 'TIMED_OUT', 'INVALID_DATA')),
    CONSTRAINT ck_booking_search_progress_candidate_count
        CHECK (candidate_count IS NULL OR candidate_count >= 0)
);

CREATE TABLE booking_search_terminal_outbox (
    id UUID PRIMARY KEY,
    search_job_id UUID NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    result_status VARCHAR(24) NOT NULL,
    candidate_count INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_search_outbox_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_search_outbox_job
        UNIQUE (search_job_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_search_outbox_event
        UNIQUE (workspace_id, created_by_user_id, event_key),
    CONSTRAINT fk_booking_search_outbox_job_owner
        FOREIGN KEY (search_job_id, workspace_id, created_by_user_id)
        REFERENCES booking_search_job (id, workspace_id, created_by_user_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_booking_search_outbox_result_status
        CHECK (result_status IN ('COMPLETE', 'PARTIAL', 'NO_RESULTS', 'FAILED')),
    CONSTRAINT ck_booking_search_outbox_candidate_count
        CHECK (candidate_count >= 0),
    CONSTRAINT ck_booking_search_outbox_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'ACKNOWLEDGED')),
    CONSTRAINT ck_booking_search_outbox_claim
        CHECK (
            (status = 'CLAIMED'
                AND claim_token IS NOT NULL
                AND claim_until IS NOT NULL)
            OR
            (status <> 'CLAIMED'
                AND claim_token IS NULL
                AND claim_until IS NULL))
);

CREATE INDEX ix_booking_search_job_cleanup
    ON booking_search_job (
        workspace_id, created_by_user_id, retention_until, id);
CREATE INDEX ix_booking_search_candidate_offer
    ON booking_search_candidate (
        workspace_id, created_by_user_id, offer_id);
CREATE INDEX ix_booking_search_outbox_claim
    ON booking_search_terminal_outbox (
        workspace_id, created_by_user_id, status, claim_until, created_at);

ALTER TABLE booking_search_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_search_job FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_search_job_owner ON booking_search_job FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_search_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_search_candidate FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_search_candidate_owner
    ON booking_search_candidate FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_search_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_search_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_search_progress_owner
    ON booking_search_progress FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_search_terminal_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_search_terminal_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_search_outbox_owner
    ON booking_search_terminal_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
