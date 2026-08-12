CREATE TABLE conversation_pending_question (
    id UUID PRIMARY KEY,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    focus_id UUID,
    root_domain VARCHAR(60) NOT NULL,
    workflow_id UUID NOT NULL,
    question_code VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    inbound_idempotency_hmac VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_conversation_pending_question_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$'
        AND scope_key_version > 0),
    CONSTRAINT chk_conversation_pending_question_code CHECK (
        question_code ~ '^[a-z0-9][a-z0-9._-]{0,119}$'),
    CONSTRAINT chk_conversation_pending_question_domain CHECK (
        root_domain ~ '^[a-z0-9][a-z0-9_-]{0,59}$'),
    CONSTRAINT chk_conversation_pending_question_status CHECK (
        status IN ('PENDING', 'ANSWERED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_conversation_pending_question_revision CHECK (revision > 0),
    CONSTRAINT chk_conversation_pending_question_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_conversation_pending_question_hmac CHECK (
        inbound_idempotency_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_conversation_pending_question_focus_scope FOREIGN KEY (
        focus_id, workspace_id, created_by_user_id, channel, conversation_scope_digest)
        REFERENCES conversation_focus (
            id, workspace_id, created_by_user_id, channel, conversation_scope_digest)
);

CREATE UNIQUE INDEX uq_conversation_pending_question_scope
    ON conversation_pending_question (
        workspace_id, created_by_user_id, channel, conversation_scope_digest)
    WHERE status = 'PENDING';

CREATE INDEX idx_conversation_pending_question_workflow
    ON conversation_pending_question (
        workspace_id, created_by_user_id, workflow_id, status, updated_at DESC);

ALTER TABLE conversation_pending_question ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_pending_question FORCE ROW LEVEL SECURITY;

CREATE POLICY rls_conversation_pending_question_actor
    ON conversation_pending_question FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE school_transport_draft
    ALTER COLUMN payload DROP NOT NULL,
    ADD COLUMN child_name VARCHAR(120),
    ADD COLUMN course_name VARCHAR(200),
    ADD COLUMN weekday VARCHAR(12),
    ADD COLUMN course_start TIME,
    ADD COLUMN course_end TIME,
    ADD COLUMN recurrence_until DATE,
    ADD COLUMN drop_person VARCHAR(120),
    ADD COLUMN drop_origin VARCHAR(200),
    ADD COLUMN drop_start TIME,
    ADD COLUMN pickup_person VARCHAR(120),
    ADD COLUMN pickup_location VARCHAR(200),
    ADD COLUMN pickup_end TIME,
    ADD COLUMN source_schedule_id BIGINT,
    ADD COLUMN source_schedule_title VARCHAR(200);

ALTER TABLE school_transport_draft
    ADD CONSTRAINT chk_school_transport_draft_representation CHECK (
        payload IS NOT NULL OR (child_name IS NOT NULL AND course_name IS NOT NULL)),
    ADD CONSTRAINT chk_school_transport_draft_course_time CHECK (
        course_start IS NULL OR course_end IS NULL OR course_end > course_start),
    ADD CONSTRAINT chk_school_transport_draft_weekday CHECK (
        weekday IS NULL OR weekday IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
            'FRIDAY', 'SATURDAY', 'SUNDAY'));

CREATE TABLE schedule_clarification_draft (
    id UUID PRIMARY KEY,
    capability VARCHAR(40) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    conversation_scope_digest VARCHAR(64) NOT NULL,
    scope_key_version INTEGER NOT NULL,
    title VARCHAR(200),
    weekday VARCHAR(12),
    ordinal_value INTEGER,
    month_offset INTEGER,
    start_time TIME,
    time_period_explicit BOOLEAN NOT NULL DEFAULT FALSE,
    duration_minutes INTEGER,
    until_date DATE,
    recurrence_explicit BOOLEAN NOT NULL DEFAULT FALSE,
    holiday_policy VARCHAR(40),
    closure_policy VARCHAR(40),
    jurisdiction VARCHAR(40),
    event_at TIMESTAMPTZ,
    primary_place VARCHAR(200),
    fallback_place VARCHAR(200),
    decision_at TIMESTAMPTZ,
    decision_period_explicit BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT chk_schedule_clarification_capability CHECK (
        capability IN ('CONDITIONAL_RECURRENCE', 'CONDITIONAL_VENUE', 'MONTHLY_ORDINAL')),
    CONSTRAINT chk_schedule_clarification_scope CHECK (
        conversation_scope_digest ~ '^[0-9a-f]{64}$' AND scope_key_version > 0),
    CONSTRAINT chk_schedule_clarification_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_schedule_clarification_revision CHECK (revision > 0),
    CONSTRAINT chk_schedule_clarification_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_schedule_clarification_ordinal CHECK (
        ordinal_value IS NULL OR ordinal_value BETWEEN 1 AND 5),
    CONSTRAINT chk_schedule_clarification_month_offset CHECK (
        month_offset IS NULL OR month_offset BETWEEN 0 AND 24),
    CONSTRAINT chk_schedule_clarification_duration CHECK (
        duration_minutes IS NULL OR duration_minutes > 0),
    CONSTRAINT chk_schedule_clarification_weekday CHECK (
        weekday IS NULL OR weekday IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
            'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT chk_schedule_clarification_places CHECK (
        primary_place IS NULL OR fallback_place IS NULL
        OR lower(primary_place) <> lower(fallback_place))
);

CREATE UNIQUE INDEX uq_schedule_clarification_pending_scope
    ON schedule_clarification_draft (
        workspace_id, created_by_user_id, channel, conversation_scope_digest, capability)
    WHERE status = 'PENDING';

ALTER TABLE schedule_clarification_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedule_clarification_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_schedule_clarification_draft_actor
    ON schedule_clarification_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
