CREATE TABLE booking_offer_snapshot (
    id UUID PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    inventory_identity VARCHAR(256) NOT NULL,
    retrieved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total_price NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    traveller_ids TEXT NOT NULL,
    terms_fingerprint VARCHAR(256) NOT NULL,
    non_refundable BOOLEAN NOT NULL,
    available BOOLEAN NOT NULL,
    capabilities TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_offer_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_offer_environment
        CHECK (environment IN ('FAKE', 'SANDBOX', 'LIVE')),
    CONSTRAINT ck_booking_offer_time
        CHECK (retrieved_at < expires_at),
    CONSTRAINT ck_booking_offer_price
        CHECK (total_price >= 0)
);

CREATE TABLE booking_purchase_authorization (
    id UUID PRIMARY KEY,
    offer_id UUID NOT NULL,
    provider VARCHAR(80) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    traveller_ids TEXT NOT NULL,
    max_total_price NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    terms_fingerprint VARCHAR(256) NOT NULL,
    non_refundable_accepted BOOLEAN NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmation_mode VARCHAR(32) NOT NULL,
    substitution_strength VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_authorization_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_authorization_offer_owner
        FOREIGN KEY (offer_id, workspace_id, created_by_user_id)
        REFERENCES booking_offer_snapshot (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_authorization_price
        CHECK (max_total_price >= 0)
);

CREATE TABLE booking_plan (
    id UUID PRIMARY KEY,
    authorization_id UUID NOT NULL,
    total_items INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_plan_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_plan_authorization_owner
        FOREIGN KEY (authorization_id, workspace_id, created_by_user_id)
        REFERENCES booking_purchase_authorization (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_plan_total
        CHECK (total_items > 0)
);

CREATE TABLE external_booking_order (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    provider VARCHAR(80) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    provider_reference VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_external_booking_order_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_external_booking_order_plan_owner
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES booking_plan (id, workspace_id, created_by_user_id)
);

CREATE TABLE booking_attempt (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    operation_id VARCHAR(160) NOT NULL,
    capability VARCHAR(32) NOT NULL,
    dispatch_state VARCHAR(32) NOT NULL,
    outcome_status VARCHAR(32),
    order_id UUID,
    public_reason VARCHAR(512) NOT NULL DEFAULT '',
    claim_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    dispatched_at TIMESTAMP WITH TIME ZONE,
    reconciled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_attempt_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_attempt_operation
        UNIQUE (workspace_id, created_by_user_id, operation_id),
    CONSTRAINT fk_booking_attempt_plan_owner
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES booking_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_attempt_order_owner
        FOREIGN KEY (order_id, workspace_id, created_by_user_id)
        REFERENCES external_booking_order (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_attempt_dispatch_state
        CHECK (dispatch_state IN ('CLAIMED', 'DISPATCHED', 'SETTLED')),
    CONSTRAINT ck_booking_attempt_outcome
        CHECK (outcome_status IS NULL OR outcome_status IN
            ('SUCCEEDED', 'FAILED', 'NEEDS_RECONCILIATION'))
);

CREATE TABLE booking_webhook_inbox (
    id UUID PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    event_key_digest VARCHAR(256) NOT NULL,
    payload_digest VARCHAR(256) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    provider_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_webhook_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_webhook_event
        UNIQUE (
            workspace_id, created_by_user_id, provider,
            environment, event_key_digest),
    CONSTRAINT ck_booking_webhook_status
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'CONFLICT'))
);

CREATE TABLE booking_execution_outbox (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT uq_booking_outbox_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT uq_booking_outbox_event
        UNIQUE (workspace_id, created_by_user_id, event_key),
    CONSTRAINT fk_booking_outbox_plan_owner
        FOREIGN KEY (plan_id, workspace_id, created_by_user_id)
        REFERENCES booking_plan (id, workspace_id, created_by_user_id),
    CONSTRAINT fk_booking_outbox_attempt_owner
        FOREIGN KEY (attempt_id, workspace_id, created_by_user_id)
        REFERENCES booking_attempt (id, workspace_id, created_by_user_id),
    CONSTRAINT ck_booking_outbox_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'ACKNOWLEDGED'))
);

CREATE INDEX ix_booking_attempt_recovery
    ON booking_attempt (
        workspace_id, created_by_user_id, dispatch_state, lease_until);
CREATE INDEX ix_booking_outbox_claim
    ON booking_execution_outbox (
        workspace_id, created_by_user_id, status, claim_until, created_at);
CREATE INDEX ix_booking_webhook_version
    ON booking_webhook_inbox (
        workspace_id, created_by_user_id, provider, environment,
        provider_version);

ALTER TABLE booking_offer_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_offer_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_offer_owner ON booking_offer_snapshot FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_purchase_authorization ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_purchase_authorization FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_authorization_owner
    ON booking_purchase_authorization FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_plan_owner ON booking_plan FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE external_booking_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_booking_order FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_external_booking_order_owner
    ON external_booking_order FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_attempt_owner ON booking_attempt FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_webhook_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_webhook_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_webhook_owner ON booking_webhook_inbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE booking_execution_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_execution_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_booking_outbox_owner ON booking_execution_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
