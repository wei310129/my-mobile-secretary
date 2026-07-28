CREATE TABLE calendar_participation_policy (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    participation_policy VARCHAR(20) NOT NULL,
    policy_revision BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participation_policy_owned_identity
        UNIQUE (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_participation_policy_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_participation_policy_plan
        FOREIGN KEY (
            plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_participation_policy_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_participation_policy_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_policy_value CHECK (
        participation_policy IN (
            'OPTIONAL', 'RECOMMENDED', 'REQUIRED')),
    CONSTRAINT chk_calendar_participation_policy_revision
        CHECK (policy_revision > 0 AND version >= 0),
    CONSTRAINT chk_calendar_participation_policy_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_participation_policy_target
    ON calendar_participation_policy (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id);

CREATE INDEX idx_calendar_participation_policy_plan
    ON calendar_participation_policy (
        workspace_id, created_by_user_id,
        plan_id, activity_id);

CREATE TABLE calendar_participation (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    share_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    participation_policy VARCHAR(20) NOT NULL,
    applied_policy_revision BIGINT,
    participation_state VARCHAR(20) NOT NULL,
    participation_revision BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participation_actor_identity
        UNIQUE (
            id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_participation_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_participation_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_participation_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_participation_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            source_created_by_user_id, created_by_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT chk_calendar_participation_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_access_shape CHECK (
        (created_by_user_id = source_created_by_user_id
            AND share_id IS NULL)
        OR (created_by_user_id <> source_created_by_user_id
            AND share_id IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_policy CHECK (
        participation_policy IN (
            'OPTIONAL', 'RECOMMENDED', 'REQUIRED')
        AND (
            applied_policy_revision IS NULL
            OR applied_policy_revision > 0)
        AND (
            applied_policy_revision IS NOT NULL
            OR participation_policy = 'OPTIONAL')),
    CONSTRAINT chk_calendar_participation_state CHECK (
        participation_state IN (
            'TENTATIVE', 'COMMITTED', 'DECLINED', 'OPTED_OUT')),
    CONSTRAINT chk_calendar_participation_revisions CHECK (
        participation_revision > 0 AND source_revision >= 0),
    CONSTRAINT chk_calendar_participation_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_participation_actor_target
    ON calendar_participation (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id, source_created_by_user_id);

CREATE INDEX idx_calendar_participation_actor_state
    ON calendar_participation (
        workspace_id, created_by_user_id,
        participation_state, plan_id, activity_id);

CREATE TABLE calendar_participation_history (
    id UUID PRIMARY KEY,
    participation_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_state VARCHAR(20),
    current_state VARCHAR(20) NOT NULL,
    participation_revision BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participation_history_revision
        UNIQUE (participation_id, participation_revision),
    CONSTRAINT uq_calendar_participation_history_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_participation_history_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_participation_history_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_participation_history_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_history_event CHECK (
        (event_type = 'PARTICIPATION_DECLARED'
            AND previous_state IS NULL
            AND participation_revision = 1)
        OR (event_type = 'STATE_CHANGED'
            AND previous_state IS NOT NULL
            AND previous_state <> current_state
            AND participation_revision > 1)),
    CONSTRAINT chk_calendar_participation_history_states CHECK (
        (previous_state IS NULL OR previous_state IN (
            'TENTATIVE', 'COMMITTED', 'DECLINED', 'OPTED_OUT'))
        AND current_state IN (
            'TENTATIVE', 'COMMITTED', 'DECLINED', 'OPTED_OUT')),
    CONSTRAINT chk_calendar_participation_history_revisions CHECK (
        participation_revision > 0 AND source_revision >= 0),
    CONSTRAINT chk_calendar_participation_history_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_calendar_participation_history_actor
    ON calendar_participation_history (
        workspace_id, created_by_user_id,
        participation_id, participation_revision);

CREATE TABLE calendar_routine_subscription (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    share_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    subscription_state VARCHAR(20) NOT NULL,
    subscription_revision BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    unsubscribed_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_routine_subscription_actor_identity
        UNIQUE (
            id, plan_id, workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_routine_subscription_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_routine_subscription_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_routine_subscription_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_routine_subscription_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            source_created_by_user_id, created_by_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT chk_calendar_routine_subscription_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_routine_subscription_access_shape CHECK (
        (created_by_user_id = source_created_by_user_id
            AND share_id IS NULL)
        OR (created_by_user_id <> source_created_by_user_id
            AND share_id IS NOT NULL)),
    CONSTRAINT chk_calendar_routine_subscription_state CHECK (
        (subscription_state = 'WATCHING'
            AND unsubscribed_at IS NULL)
        OR (subscription_state = 'UNSUBSCRIBED'
            AND unsubscribed_at IS NOT NULL)),
    CONSTRAINT chk_calendar_routine_subscription_revisions CHECK (
        subscription_revision > 0 AND source_revision >= 0),
    CONSTRAINT chk_calendar_routine_subscription_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_routine_subscription_actor_target
    ON calendar_routine_subscription (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id, source_created_by_user_id);

CREATE INDEX idx_calendar_routine_subscription_actor_state
    ON calendar_routine_subscription (
        workspace_id, created_by_user_id,
        subscription_state, plan_id, activity_id);

CREATE TABLE calendar_personal_projection_signal (
    id UUID PRIMARY KEY,
    signal_kind VARCHAR(30) NOT NULL,
    source_outbox_id UUID,
    authoritative_mutation_id UUID,
    share_id UUID,
    plan_id UUID NOT NULL,
    source_node_id UUID,
    source_revision BIGINT NOT NULL,
    recipient_user_id UUID,
    mutation_mode VARCHAR(40),
    mutation_kind VARCHAR(30),
    source_origin VARCHAR(30),
    payload_text VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_projection_signal_identity
        UNIQUE (
            id, signal_kind, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_projection_signal_recipient_identity
        UNIQUE (
            id, signal_kind, workspace_id,
            created_by_user_id, recipient_user_id),
    CONSTRAINT fk_calendar_projection_signal_outbox
        FOREIGN KEY (source_outbox_id)
        REFERENCES calendar_share_outbox (id),
    CONSTRAINT fk_calendar_projection_signal_authoritative_mutation
        FOREIGN KEY (
            workspace_id, authoritative_mutation_id)
        REFERENCES calendar_authoritative_mutation_audit (
            workspace_id, mutation_id),
    CONSTRAINT fk_calendar_projection_signal_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            created_by_user_id, recipient_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_projection_signal_plan
        FOREIGN KEY (
            plan_id, workspace_id, created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_projection_signal_node
        FOREIGN KEY (
            source_node_id, plan_id, workspace_id,
            created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_projection_signal_shape CHECK (
        (signal_kind = 'SOURCE_MUTATION'
            AND share_id IS NULL
            AND source_node_id IS NOT NULL
            AND recipient_user_id IS NULL
            AND mutation_kind IN (
                'NODE_TIME', 'NODE_LOCATION',
                'NODE_CANCELLATION')
            AND (
                (mutation_mode = 'GENERAL_REVIEW'
                    AND source_origin = 'OWNER'
                    AND source_outbox_id IS NULL
                    AND authoritative_mutation_id IS NULL)
                OR
                (mutation_mode =
                        'AUTHORITATIVE_AUTO_APPLY'
                    AND source_origin =
                        'AUTHORIZED_EDITOR'
                    AND source_outbox_id IS NOT NULL
                    AND authoritative_mutation_id
                        IS NOT NULL)))
        OR (signal_kind = 'SHARE_REVOKED'
            AND authoritative_mutation_id IS NULL
            AND share_id IS NOT NULL
            AND source_node_id IS NULL
            AND recipient_user_id IS NOT NULL
            AND mutation_mode IS NULL
            AND mutation_kind IS NULL
            AND source_origin IS NULL)),
    CONSTRAINT chk_calendar_projection_signal_revision
        CHECK (source_revision > 0),
    CONSTRAINT chk_calendar_projection_signal_recipient CHECK (
        recipient_user_id IS NULL
        OR recipient_user_id <> created_by_user_id),
    CONSTRAINT chk_calendar_projection_signal_payload
        CHECK (length(btrim(payload_text)) BETWEEN 1 AND 1000)
);

CREATE UNIQUE INDEX uq_calendar_projection_signal_authoritative_recipient
    ON calendar_personal_projection_signal (
        workspace_id, authoritative_mutation_id)
    WHERE authoritative_mutation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_calendar_projection_signal_source_revision
    ON calendar_personal_projection_signal (
        workspace_id, created_by_user_id,
        source_node_id, source_revision, mutation_mode)
    WHERE signal_kind = 'SOURCE_MUTATION';

CREATE UNIQUE INDEX uq_calendar_projection_signal_share_revoke
    ON calendar_personal_projection_signal (
        workspace_id, share_id, recipient_user_id)
    WHERE share_id IS NOT NULL;

CREATE INDEX idx_calendar_projection_signal_source
    ON calendar_personal_projection_signal (
        workspace_id, created_by_user_id, created_at, id);

CREATE TABLE calendar_personal_projection_snapshot (
    id UUID PRIMARY KEY,
    adoption_id UUID NOT NULL,
    participation_id UUID,
    plan_id UUID NOT NULL,
    plan_title VARCHAR(200) NOT NULL,
    placement_kind VARCHAR(20) NOT NULL,
    timed_start TIMESTAMPTZ,
    timed_end TIMESTAMPTZ,
    zone_id VARCHAR(64),
    all_day_start DATE,
    all_day_end_exclusive DATE,
    source_signal_id UUID,
    source_signal_kind VARCHAR(30),
    source_signal_owner_user_id UUID,
    source_signal_recipient_user_id UUID,
    projection_revision BIGINT NOT NULL,
    source_plan_revision BIGINT NOT NULL,
    projection_status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_projection_snapshot_actor_identity
        UNIQUE (
            id, adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_projection_snapshot_revision
        UNIQUE (adoption_id, projection_revision),
    CONSTRAINT fk_calendar_projection_snapshot_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_snapshot_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_snapshot_signal
        FOREIGN KEY (
            source_signal_id, source_signal_kind, workspace_id,
            source_signal_owner_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_projection_snapshot_recipient_signal
        FOREIGN KEY (
            source_signal_id, source_signal_kind, workspace_id,
            source_signal_owner_user_id,
            source_signal_recipient_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id,
            created_by_user_id, recipient_user_id),
    CONSTRAINT chk_calendar_projection_snapshot_signal_shape CHECK (
        (source_signal_id IS NULL
            AND source_signal_kind IS NULL
            AND source_signal_owner_user_id IS NULL)
            AND source_signal_recipient_user_id IS NULL
        OR (source_signal_id IS NOT NULL
            AND source_signal_kind = 'SOURCE_MUTATION'
            AND source_signal_owner_user_id =
                source_created_by_user_id
            AND source_signal_recipient_user_id IS NULL)
        OR (source_signal_id IS NOT NULL
            AND source_signal_kind = 'SHARE_REVOKED'
            AND source_signal_owner_user_id =
                source_created_by_user_id
            AND source_signal_recipient_user_id =
                created_by_user_id)),
    CONSTRAINT chk_calendar_projection_snapshot_revisions CHECK (
        projection_revision > 0 AND source_plan_revision >= 0),
    CONSTRAINT chk_calendar_projection_snapshot_title
        CHECK (length(btrim(plan_title)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_projection_snapshot_placement CHECK (
        (placement_kind = 'TIMED_INTERVAL'
            AND timed_start IS NOT NULL
            AND timed_end IS NOT NULL
            AND timed_end > timed_start
            AND zone_id IS NOT NULL
            AND all_day_start IS NULL
            AND all_day_end_exclusive IS NULL)
        OR (placement_kind = 'TIMED_POINT'
            AND timed_start IS NOT NULL
            AND timed_end IS NULL
            AND zone_id IS NOT NULL
            AND all_day_start IS NULL
            AND all_day_end_exclusive IS NULL)
        OR (placement_kind = 'ALL_DAY'
            AND timed_start IS NULL
            AND timed_end IS NULL
            AND zone_id IS NULL
            AND all_day_start IS NOT NULL
            AND all_day_end_exclusive > all_day_start)),
    CONSTRAINT chk_calendar_projection_snapshot_status CHECK (
        projection_status IN (
            'ACTIVE', 'REVIEW_REQUIRED',
            'RETAINED_NO_SOURCE_ACCESS', 'SUPERSEDED'))
);

CREATE INDEX idx_calendar_projection_snapshot_actor
    ON calendar_personal_projection_snapshot (
        workspace_id, created_by_user_id,
        adoption_id, projection_revision DESC);

CREATE TABLE calendar_personal_projection_node (
    snapshot_id UUID NOT NULL,
    adoption_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    activity_id UUID,
    node_key VARCHAR(100) NOT NULL,
    label VARCHAR(200) NOT NULL,
    expression_kind VARCHAR(30) NOT NULL,
    absolute_time TIMESTAMPTZ,
    offset_seconds BIGINT,
    base_node_key VARCHAR(100),
    resolved_time TIMESTAMPTZ NOT NULL,
    criticality VARCHAR(20) NOT NULL,
    adjustability VARCHAR(20) NOT NULL,
    location_label VARCHAR(200),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    cancellation_status VARCHAR(20) NOT NULL,
    canceled_at TIMESTAMPTZ,
    source_node_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    PRIMARY KEY (
        snapshot_id, source_node_id,
        workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_projection_node_snapshot
        FOREIGN KEY (
            snapshot_id, adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_personal_projection_snapshot (
            id, adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_node_source
        FOREIGN KEY (
            source_node_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_projection_node_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_projection_node_key
        CHECK (length(btrim(node_key)) BETWEEN 1 AND 100),
    CONSTRAINT chk_calendar_projection_node_label
        CHECK (length(btrim(label)) BETWEEN 1 AND 200),
    CONSTRAINT chk_calendar_projection_node_criticality
        CHECK (criticality IN ('CRITICAL', 'NORMAL')),
    CONSTRAINT chk_calendar_projection_node_adjustability
        CHECK (adjustability IN (
            'LOCKED', 'WINDOWED', 'FLEXIBLE')),
    CONSTRAINT chk_calendar_projection_node_expression CHECK (
        (expression_kind = 'ABSOLUTE'
            AND absolute_time IS NOT NULL
            AND offset_seconds IS NULL
            AND base_node_key IS NULL)
        OR (expression_kind IN (
                'OWNER_START_OFFSET', 'OWNER_END_OFFSET')
            AND absolute_time IS NULL
            AND offset_seconds IS NOT NULL
            AND base_node_key IS NULL)
        OR (expression_kind = 'NODE_OFFSET'
            AND absolute_time IS NULL
            AND offset_seconds IS NOT NULL
            AND base_node_key IS NOT NULL
            AND base_node_key <> node_key)),
    CONSTRAINT chk_calendar_projection_node_location CHECK (
        (location_label IS NULL
            AND latitude IS NULL
            AND longitude IS NULL)
        OR (length(btrim(location_label)) BETWEEN 1 AND 200
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180)),
    CONSTRAINT chk_calendar_projection_node_cancellation CHECK (
        (cancellation_status = 'ACTIVE' AND canceled_at IS NULL)
        OR (cancellation_status = 'CANCELED'
            AND canceled_at IS NOT NULL)),
    CONSTRAINT chk_calendar_projection_node_revision
        CHECK (source_node_revision > 0)
);

CREATE INDEX idx_calendar_projection_node_actor
    ON calendar_personal_projection_node (
        workspace_id, created_by_user_id,
        snapshot_id, activity_id, node_key);

CREATE TABLE calendar_skip_confirmation (
    id UUID PRIMARY KEY,
    adoption_id UUID,
    participation_id UUID,
    plan_id UUID NOT NULL,
    activity_id UUID,
    share_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    expected_participation_revision BIGINT,
    expected_projection_revision BIGINT,
    expected_source_revision BIGINT NOT NULL,
    preview_token_hash VARCHAR(64) NOT NULL,
    impact_preview_hash VARCHAR(64) NOT NULL,
    previewed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmation_request_hash VARCHAR(64),
    confirmation_payload_hash VARCHAR(64),
    consumed_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_skip_confirmation_actor_identity
        UNIQUE (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_skip_confirmation_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_skip_confirmation_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_skip_confirmation_share
        FOREIGN KEY (
            share_id, plan_id, workspace_id,
            source_created_by_user_id, created_by_user_id)
        REFERENCES calendar_share (
            id, plan_id, workspace_id,
            created_by_user_id, grantee_user_id),
    CONSTRAINT fk_calendar_skip_confirmation_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_skip_confirmation_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_skip_confirmation_access_shape CHECK (
        (created_by_user_id = source_created_by_user_id
            AND share_id IS NULL)
        OR (created_by_user_id <> source_created_by_user_id
            AND share_id IS NOT NULL)),
    CONSTRAINT chk_calendar_skip_confirmation_revision CHECK (
        expected_source_revision >= 0
        AND (
            expected_participation_revision IS NULL
            OR expected_participation_revision > 0)
        AND (
            expected_projection_revision IS NULL
            OR expected_projection_revision > 0)
        AND (
            (participation_id IS NULL
                AND expected_participation_revision IS NULL)
            OR (participation_id IS NOT NULL
                AND expected_participation_revision IS NOT NULL))
        AND (
            (adoption_id IS NULL
                AND expected_projection_revision IS NULL)
            OR (adoption_id IS NOT NULL
                AND expected_projection_revision IS NOT NULL))),
    CONSTRAINT chk_calendar_skip_confirmation_expiry
        CHECK (expires_at > previewed_at),
    CONSTRAINT chk_calendar_skip_confirmation_lifecycle CHECK (
        (consumed_at IS NULL
            AND confirmation_request_hash IS NULL
            AND confirmation_payload_hash IS NULL)
        OR (consumed_at IS NOT NULL
            AND consumed_at >= previewed_at
            AND consumed_at <= expires_at
            AND confirmation_request_hash IS NOT NULL
            AND confirmation_payload_hash IS NOT NULL)),
    CONSTRAINT chk_calendar_skip_confirmation_hashes CHECK (
        preview_token_hash ~ '^[0-9a-f]{64}$'
        AND impact_preview_hash ~ '^[0-9a-f]{64}$'
        AND (
            confirmation_request_hash IS NULL
            OR confirmation_request_hash ~ '^[0-9a-f]{64}$')
        AND (
            confirmation_payload_hash IS NULL
            OR confirmation_payload_hash ~ '^[0-9a-f]{64}$'))
);

CREATE UNIQUE INDEX uq_calendar_skip_confirmation_token
    ON calendar_skip_confirmation (
        workspace_id, created_by_user_id, preview_token_hash);

CREATE UNIQUE INDEX uq_calendar_skip_confirmation_request
    ON calendar_skip_confirmation (
        workspace_id, created_by_user_id,
        confirmation_request_hash)
    WHERE confirmation_request_hash IS NOT NULL;

CREATE TABLE calendar_projection_suppression (
    id UUID PRIMARY KEY,
    confirmation_id UUID NOT NULL,
    adoption_id UUID,
    participation_id UUID,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    participation_revision BIGINT,
    projection_revision BIGINT,
    source_revision BIGINT NOT NULL,
    suppression_revision BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    constraints_suppressed BOOLEAN NOT NULL,
    future_reminders_suppressed BOOLEAN NOT NULL,
    routine_messages_suppressed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_projection_suppression_actor_identity
        UNIQUE (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_projection_suppression_confirmation
        UNIQUE (confirmation_id),
    CONSTRAINT fk_calendar_projection_suppression_confirmation
        FOREIGN KEY (
            confirmation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_skip_confirmation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_suppression_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_suppression_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_projection_suppression_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_projection_suppression_target CHECK (
        (target_scope = 'PLAN' AND activity_id IS NULL)
        OR (target_scope = 'ACTIVITY' AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_projection_suppression_revisions CHECK (
        source_revision >= 0
        AND suppression_revision > 0
        AND (
            (participation_id IS NULL
                AND participation_revision IS NULL)
            OR (participation_id IS NOT NULL
                AND participation_revision > 0))
        AND (
            (adoption_id IS NULL
                AND projection_revision IS NULL)
            OR (adoption_id IS NOT NULL
                AND projection_revision > 0))),
    CONSTRAINT chk_calendar_projection_suppression_effect CHECK (
        constraints_suppressed
        AND future_reminders_suppressed
        AND routine_messages_suppressed),
    CONSTRAINT chk_calendar_projection_suppression_lifecycle CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_calendar_projection_suppression_active_target
    ON calendar_projection_suppression (
        plan_id, target_scope,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id, source_created_by_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_calendar_projection_suppression_actor
    ON calendar_projection_suppression (
        workspace_id, created_by_user_id,
        status, plan_id, activity_id);

CREATE TABLE calendar_authoritative_recipient_receipt (
    id UUID PRIMARY KEY,
    signal_id UUID NOT NULL,
    signal_kind VARCHAR(30) NOT NULL,
    source_signal_owner_user_id UUID NOT NULL,
    signal_recipient_user_id UUID,
    adoption_id UUID,
    participation_id UUID,
    routine_subscription_id UUID,
    personal_snapshot_id UUID,
    plan_id UUID NOT NULL,
    disposition VARCHAR(40) NOT NULL,
    delivery_status VARCHAR(20) NOT NULL,
    delivery_attempt_count INTEGER NOT NULL,
    next_delivery_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_delivery_failure VARCHAR(500),
    acknowledgement_status VARCHAR(20) NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    receipt_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_authoritative_receipt_signal
        UNIQUE (signal_id, workspace_id, created_by_user_id),
    CONSTRAINT uq_calendar_authoritative_receipt_actor_identity
        UNIQUE (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_signal
        FOREIGN KEY (
            signal_id, signal_kind, workspace_id,
            source_signal_owner_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_recipient_signal
        FOREIGN KEY (
            signal_id, signal_kind, workspace_id,
            source_signal_owner_user_id, signal_recipient_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id,
            created_by_user_id, recipient_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_adoption
        FOREIGN KEY (
            adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_adoption (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_subscription
        FOREIGN KEY (
            routine_subscription_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_routine_subscription (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_authoritative_receipt_snapshot
        FOREIGN KEY (
            personal_snapshot_id, adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_personal_projection_snapshot (
            id, adoption_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_authoritative_receipt_disposition CHECK (
        (disposition = 'AUTO_APPLIED'
            AND signal_kind = 'SOURCE_MUTATION'
            AND signal_recipient_user_id IS NULL
            AND adoption_id IS NOT NULL
            AND participation_id IS NOT NULL
            AND routine_subscription_id IS NULL
            AND personal_snapshot_id IS NOT NULL)
        OR (disposition = 'REVIEW_REQUIRED'
            AND signal_kind = 'SOURCE_MUTATION'
            AND signal_recipient_user_id IS NULL
            AND participation_id IS NOT NULL
            AND routine_subscription_id IS NULL)
        OR (disposition = 'ROUTINE_UPDATE'
            AND signal_kind = 'SOURCE_MUTATION'
            AND signal_recipient_user_id IS NULL
            AND participation_id IS NULL
            AND routine_subscription_id IS NOT NULL
            AND personal_snapshot_id IS NULL)
        OR (disposition = 'SOURCE_ACCESS_REVOKED'
            AND signal_kind = 'SHARE_REVOKED'
            AND signal_recipient_user_id = created_by_user_id
            AND participation_id IS NULL
            AND routine_subscription_id IS NULL)
        OR (disposition = 'ACCESS_RETAINED'
            AND signal_kind = 'SHARE_REVOKED'
            AND signal_recipient_user_id = created_by_user_id
            AND adoption_id IS NULL
            AND participation_id IS NULL
            AND routine_subscription_id IS NULL
            AND personal_snapshot_id IS NULL)
        OR (disposition = 'SUPPRESSED'
            AND signal_kind = 'SOURCE_MUTATION'
            AND signal_recipient_user_id IS NULL
            AND adoption_id IS NULL
            AND participation_id IS NOT NULL
            AND routine_subscription_id IS NULL
            AND personal_snapshot_id IS NULL)
        OR (disposition = 'NO_ACTION'
            AND signal_kind = 'SOURCE_MUTATION'
            AND signal_recipient_user_id IS NULL
            AND adoption_id IS NULL
            AND participation_id IS NULL
            AND routine_subscription_id IS NULL
            AND personal_snapshot_id IS NULL)),
    CONSTRAINT chk_calendar_authoritative_receipt_delivery CHECK (
        (delivery_status = 'NOT_REQUIRED'
            AND delivery_attempt_count = 0
            AND next_delivery_attempt_at IS NULL
            AND delivered_at IS NULL
            AND last_delivery_failure IS NULL)
        OR (delivery_status = 'PENDING'
            AND delivered_at IS NULL
            AND last_delivery_failure IS NULL)
        OR (delivery_status = 'SENT'
            AND delivered_at IS NOT NULL
            AND next_delivery_attempt_at IS NULL
            AND last_delivery_failure IS NULL)
        OR (delivery_status = 'FAILED'
            AND delivered_at IS NULL
            AND last_delivery_failure IS NOT NULL)),
    CONSTRAINT chk_calendar_authoritative_receipt_attempts
        CHECK (delivery_attempt_count >= 0),
    CONSTRAINT chk_calendar_authoritative_receipt_acknowledgement CHECK (
        (
            (disposition = 'AUTO_APPLIED'
                AND acknowledgement_status IN (
                    'AWAITING', 'ACKNOWLEDGED'))
            OR (disposition <> 'AUTO_APPLIED'
                AND acknowledgement_status = 'NOT_REQUIRED')
        )
        AND (
            (acknowledgement_status = 'ACKNOWLEDGED'
                AND acknowledged_at IS NOT NULL)
            OR (acknowledgement_status <> 'ACKNOWLEDGED'
                AND acknowledged_at IS NULL))),
    CONSTRAINT chk_calendar_authoritative_receipt_revision
        CHECK (receipt_revision > 0),
    CONSTRAINT chk_calendar_authoritative_receipt_failure
        CHECK (
            last_delivery_failure IS NULL
            OR length(btrim(last_delivery_failure)) BETWEEN 1 AND 500),
    CONSTRAINT chk_calendar_authoritative_receipt_source_owner CHECK (
        source_signal_owner_user_id = source_created_by_user_id
        AND created_by_user_id <> source_created_by_user_id)
);

CREATE INDEX idx_calendar_authoritative_receipt_actor_delivery
    ON calendar_authoritative_recipient_receipt (
        workspace_id, created_by_user_id,
        delivery_status, next_delivery_attempt_at, created_at);

CREATE TABLE calendar_participation_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    signal_id UUID,
    signal_kind VARCHAR(30),
    source_signal_owner_user_id UUID,
    signal_recipient_user_id UUID,
    receipt_id UUID,
    suppression_id UUID,
    plan_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    semantic_identity_hash VARCHAR(64) NOT NULL,
    payload_text VARCHAR(1000) NOT NULL,
    delivery_status VARCHAR(20) NOT NULL,
    delivery_attempt_count INTEGER NOT NULL,
    next_delivery_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_delivery_failure VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participation_outbox_request
        UNIQUE (
            workspace_id, created_by_user_id,
            event_type, operation_request_hash),
    CONSTRAINT uq_calendar_participation_outbox_semantic
        UNIQUE (
            workspace_id, created_by_user_id,
            event_type, semantic_identity_hash),
    CONSTRAINT fk_calendar_participation_outbox_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_participation_outbox_signal
        FOREIGN KEY (
            signal_id, signal_kind, workspace_id,
            source_signal_owner_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_participation_outbox_recipient_signal
        FOREIGN KEY (
            signal_id, signal_kind, workspace_id,
            source_signal_owner_user_id, signal_recipient_user_id)
        REFERENCES calendar_personal_projection_signal (
            id, signal_kind, workspace_id,
            created_by_user_id, recipient_user_id),
    CONSTRAINT fk_calendar_participation_outbox_receipt
        FOREIGN KEY (
            receipt_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_authoritative_recipient_receipt (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_participation_outbox_suppression
        FOREIGN KEY (
            suppression_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_projection_suppression (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_participation_outbox_shape CHECK (
        (event_type = 'SKIP_CONFIRMED'
            AND signal_id IS NULL
            AND signal_kind IS NULL
            AND source_signal_owner_user_id IS NULL
            AND signal_recipient_user_id IS NULL
            AND receipt_id IS NULL
            AND suppression_id IS NOT NULL)
        OR (event_type IN (
                'REVIEW_REQUIRED_UPDATE',
                'ROUTINE_UPDATE',
                'MANDATORY_UPDATE')
            AND signal_id IS NOT NULL
            AND signal_kind = 'SOURCE_MUTATION'
            AND source_signal_owner_user_id =
                source_created_by_user_id
            AND signal_recipient_user_id IS NULL
            AND receipt_id IS NOT NULL
            AND suppression_id IS NULL)
        OR (event_type = 'SHARE_ACCESS_REVOKED'
            AND signal_id IS NOT NULL
            AND signal_kind = 'SHARE_REVOKED'
            AND source_signal_owner_user_id =
                source_created_by_user_id
            AND signal_recipient_user_id = created_by_user_id
            AND receipt_id IS NOT NULL
            AND suppression_id IS NULL)),
    CONSTRAINT chk_calendar_participation_outbox_revision
        CHECK (source_revision >= 0),
    CONSTRAINT chk_calendar_participation_outbox_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$'
        AND semantic_identity_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_participation_outbox_payload
        CHECK (length(btrim(payload_text)) BETWEEN 1 AND 1000),
    CONSTRAINT chk_calendar_participation_outbox_delivery CHECK (
        (delivery_status = 'PENDING'
            AND delivered_at IS NULL
            AND last_delivery_failure IS NULL)
        OR (delivery_status = 'SENT'
            AND delivered_at IS NOT NULL
            AND next_delivery_attempt_at IS NULL
            AND last_delivery_failure IS NULL)
        OR (delivery_status = 'FAILED'
            AND delivered_at IS NULL
            AND last_delivery_failure IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_outbox_attempts
        CHECK (delivery_attempt_count >= 0),
    CONSTRAINT chk_calendar_participation_outbox_failure
        CHECK (
            last_delivery_failure IS NULL
            OR length(btrim(last_delivery_failure)) BETWEEN 1 AND 500)
);

CREATE INDEX idx_calendar_participation_outbox_delivery
    ON calendar_participation_outbox (
        workspace_id, created_by_user_id,
        delivery_status, next_delivery_attempt_at, created_at);

-- V83 adoptions predate durable personal copies. The migration transaction
-- temporarily exposes only the four source tables needed for this backfill
-- and restores FORCE RLS before it can commit.
ALTER TABLE calendar_plan NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_plan DISABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node DISABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption DISABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node DISABLE ROW LEVEL SECURITY;

INSERT INTO calendar_personal_projection_snapshot (
    id, adoption_id, participation_id, plan_id,
    plan_title, placement_kind, timed_start, timed_end, zone_id,
    all_day_start, all_day_end_exclusive,
    source_signal_id, source_signal_kind,
    source_signal_owner_user_id,
    source_signal_recipient_user_id,
    projection_revision, source_plan_revision,
    projection_status, created_at, workspace_id,
    created_by_user_id, source_created_by_user_id)
SELECT
    gen_random_uuid(), adoption.id, NULL, plan.id,
    plan.title, plan.placement_kind, plan.timed_start, plan.timed_end,
    plan.zone_id, plan.all_day_start, plan.all_day_end_exclusive,
    NULL, NULL, NULL, NULL,
    1, plan.version + 1,
    CASE
        WHEN EXISTS (
            SELECT 1
            FROM calendar_adoption_node selected
            JOIN calendar_time_node node
              ON node.id = selected.node_id
             AND node.plan_id = selected.plan_id
             AND node.workspace_id = selected.workspace_id
             AND node.created_by_user_id =
                    selected.source_created_by_user_id
            WHERE selected.adoption_id = adoption.id
              AND selected.workspace_id = adoption.workspace_id
              AND selected.created_by_user_id =
                    adoption.created_by_user_id
              AND selected.node_revision <> node.revision)
        THEN 'REVIEW_REQUIRED'
        ELSE 'ACTIVE'
    END,
    adoption.created_at,
    adoption.workspace_id, adoption.created_by_user_id,
    adoption.source_created_by_user_id
FROM calendar_adoption adoption
JOIN calendar_plan plan
  ON plan.id = adoption.plan_id
 AND plan.workspace_id = adoption.workspace_id
 AND plan.created_by_user_id =
        adoption.source_created_by_user_id
WHERE adoption.status = 'ACTIVE';

INSERT INTO calendar_personal_projection_node (
    snapshot_id, adoption_id, plan_id, source_node_id,
    activity_id, node_key, label, expression_kind,
    absolute_time, offset_seconds, base_node_key, resolved_time,
    criticality, adjustability, location_label, latitude, longitude,
    cancellation_status, canceled_at, source_node_revision,
    created_at, workspace_id, created_by_user_id,
    source_created_by_user_id)
SELECT
    snapshot.id, adoption.id, adoption.plan_id, node.id,
    node.activity_id, node.node_key, node.label,
    node.expression_kind, node.absolute_time, node.offset_seconds,
    node.base_node_key, node.resolved_time, node.criticality,
    node.adjustability, node.location_label, node.latitude,
    node.longitude, node.cancellation_status, node.canceled_at,
    node.revision, selected.created_at,
    adoption.workspace_id, adoption.created_by_user_id,
    adoption.source_created_by_user_id
FROM calendar_adoption adoption
JOIN calendar_personal_projection_snapshot snapshot
  ON snapshot.adoption_id = adoption.id
 AND snapshot.workspace_id = adoption.workspace_id
 AND snapshot.created_by_user_id =
        adoption.created_by_user_id
JOIN calendar_adoption_node selected
  ON selected.adoption_id = adoption.id
 AND selected.plan_id = adoption.plan_id
 AND selected.workspace_id = adoption.workspace_id
 AND selected.created_by_user_id =
        adoption.created_by_user_id
 AND selected.source_created_by_user_id =
        adoption.source_created_by_user_id
JOIN calendar_time_node node
  ON node.id = selected.node_id
 AND node.plan_id = selected.plan_id
 AND node.workspace_id = selected.workspace_id
 AND node.created_by_user_id =
        selected.source_created_by_user_id
WHERE adoption.status = 'ACTIVE';

ALTER TABLE calendar_adoption_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption_node FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_adoption FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_time_node FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_plan FORCE ROW LEVEL SECURITY;

CREATE UNIQUE INDEX uq_calendar_projection_snapshot_current
    ON calendar_personal_projection_snapshot (adoption_id)
    WHERE projection_status <> 'SUPERSEDED';

ALTER TABLE calendar_participation_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participation_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participation_policy_owner_select
    ON calendar_participation_policy FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_policy_owner_insert
    ON calendar_participation_policy FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_policy_owner_update
    ON calendar_participation_policy FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_policy_grantee_select
    ON calendar_participation_policy FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.plan_id =
                calendar_participation_policy.plan_id
              AND share.workspace_id =
                calendar_participation_policy.workspace_id
              AND share.created_by_user_id =
                calendar_participation_policy.created_by_user_id
              AND app_actor_matches(share.grantee_user_id)
              AND share.status = 'ACTIVE'
              AND (
                  share.scope_mode = 'LIVE_WHOLE_PLAN'
                  OR (
                      calendar_participation_policy.target_scope =
                          'ACTIVITY'
                      AND share.scope_mode =
                          'SELECTED_ACTIVITIES'
                      AND EXISTS (
                          SELECT 1
                          FROM calendar_share_scope_item item
                          WHERE item.snapshot_id =
                                share.current_scope_snapshot_id
                            AND item.share_id = share.id
                            AND item.activity_id =
                                calendar_participation_policy
                                    .activity_id
                            AND item.target_kind =
                                'ACTIVITY')))));

ALTER TABLE calendar_participation ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participation FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participation_actor_select
    ON calendar_participation FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_source_owner_select
    ON calendar_participation FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id));
CREATE POLICY rls_calendar_participation_actor_insert
    ON calendar_participation FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND (
            (created_by_user_id = source_created_by_user_id
                AND share_id IS NULL)
            OR EXISTS (
                SELECT 1
                FROM calendar_share share
                WHERE share.id = calendar_participation.share_id
                  AND share.plan_id = calendar_participation.plan_id
                  AND share.workspace_id =
                      calendar_participation.workspace_id
                  AND share.created_by_user_id =
                      calendar_participation.source_created_by_user_id
                  AND share.grantee_user_id =
                      calendar_participation.created_by_user_id
                  AND share.status = 'ACTIVE'
                  AND (
                      share.scope_mode = 'LIVE_WHOLE_PLAN'
                      OR (
                          calendar_participation.target_scope =
                              'ACTIVITY'
                          AND share.scope_mode =
                              'SELECTED_ACTIVITIES'
                          AND EXISTS (
                              SELECT 1
                              FROM calendar_share_scope_item item
                              WHERE item.snapshot_id =
                                    share.current_scope_snapshot_id
                                AND item.share_id = share.id
                                AND item.activity_id =
                                    calendar_participation
                                        .activity_id
                                AND item.target_kind =
                                    'ACTIVITY')))))
        AND (
            (participation_policy = 'OPTIONAL'
                AND applied_policy_revision IS NULL)
            OR EXISTS (
                SELECT 1
                FROM calendar_participation_policy policy
                WHERE policy.plan_id =
                    calendar_participation.plan_id
                  AND policy.activity_id IS NOT DISTINCT FROM
                    calendar_participation.activity_id
                  AND policy.target_scope =
                    calendar_participation.target_scope
                  AND policy.participation_policy =
                    calendar_participation.participation_policy
                  AND policy.policy_revision =
                    calendar_participation.applied_policy_revision
                  AND policy.workspace_id =
                    calendar_participation.workspace_id
                  AND policy.created_by_user_id =
                    calendar_participation
                        .source_created_by_user_id)));
CREATE POLICY rls_calendar_participation_actor_update
    ON calendar_participation FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_participation_history
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participation_history
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participation_history_actor_select
    ON calendar_participation_history FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_history_source_owner_select
    ON calendar_participation_history FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id));
CREATE POLICY rls_calendar_participation_history_actor_insert
    ON calendar_participation_history FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_routine_subscription
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_routine_subscription
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_routine_subscription_actor_select
    ON calendar_routine_subscription FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_routine_subscription_actor_insert
    ON calendar_routine_subscription FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND (
            (created_by_user_id = source_created_by_user_id
                AND share_id IS NULL)
            OR EXISTS (
                SELECT 1
                FROM calendar_share share
                WHERE share.id = calendar_routine_subscription.share_id
                  AND share.plan_id =
                      calendar_routine_subscription.plan_id
                  AND share.workspace_id =
                      calendar_routine_subscription.workspace_id
                  AND share.created_by_user_id =
                      calendar_routine_subscription
                          .source_created_by_user_id
                  AND share.grantee_user_id =
                      calendar_routine_subscription.created_by_user_id
                  AND share.status = 'ACTIVE'
                  AND (
                      share.scope_mode = 'LIVE_WHOLE_PLAN'
                      OR (
                          calendar_routine_subscription
                              .target_scope = 'ACTIVITY'
                          AND share.scope_mode =
                              'SELECTED_ACTIVITIES'
                          AND EXISTS (
                              SELECT 1
                              FROM calendar_share_scope_item item
                              WHERE item.snapshot_id =
                                    share.current_scope_snapshot_id
                                AND item.share_id = share.id
                                AND item.activity_id =
                                    calendar_routine_subscription
                                        .activity_id
                                AND item.target_kind =
                                    'ACTIVITY'))))));
CREATE POLICY rls_calendar_routine_subscription_actor_update
    ON calendar_routine_subscription FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_personal_projection_signal
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_personal_projection_signal
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_projection_signal_owner_select
    ON calendar_personal_projection_signal FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_signal_recipient_select
    ON calendar_personal_projection_signal FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            (signal_kind = 'SHARE_REVOKED'
                AND app_actor_matches(recipient_user_id))
            OR (
                signal_kind = 'SOURCE_MUTATION'
                AND (
                    EXISTS (
                        SELECT 1
                        FROM calendar_participation participation
                        WHERE participation.plan_id =
                            calendar_personal_projection_signal.plan_id
                          AND participation.workspace_id =
                            calendar_personal_projection_signal
                                .workspace_id
                          AND participation.source_created_by_user_id =
                            calendar_personal_projection_signal
                                .created_by_user_id
                          AND (
                              participation.target_scope = 'PLAN'
                              OR (
                                  participation.target_scope =
                                      'ACTIVITY'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM calendar_time_node node
                                      WHERE node.id =
                                            calendar_personal_projection_signal
                                                .source_node_id
                                        AND node.activity_id =
                                            participation.activity_id)))
                          AND app_actor_matches(
                              participation.created_by_user_id))
                    OR EXISTS (
                        SELECT 1
                        FROM calendar_routine_subscription subscription
                        WHERE subscription.plan_id =
                            calendar_personal_projection_signal.plan_id
                          AND subscription.workspace_id =
                            calendar_personal_projection_signal
                                .workspace_id
                          AND subscription.source_created_by_user_id =
                            calendar_personal_projection_signal
                                .created_by_user_id
                          AND subscription.subscription_state =
                              'WATCHING'
                          AND (
                              subscription.target_scope = 'PLAN'
                              OR (
                                  subscription.target_scope =
                                      'ACTIVITY'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM calendar_time_node node
                                      WHERE node.id =
                                            calendar_personal_projection_signal
                                                .source_node_id
                                        AND node.activity_id =
                                            subscription.activity_id)))
                          AND app_actor_matches(
                              subscription.created_by_user_id))
                    OR EXISTS (
                        SELECT 1
                        FROM calendar_adoption adoption
                        JOIN calendar_adoption_node selected
                          ON selected.adoption_id = adoption.id
                         AND selected.plan_id = adoption.plan_id
                         AND selected.workspace_id =
                                adoption.workspace_id
                         AND selected.created_by_user_id =
                                adoption.created_by_user_id
                         AND selected.source_created_by_user_id =
                                adoption.source_created_by_user_id
                        WHERE adoption.plan_id =
                            calendar_personal_projection_signal.plan_id
                          AND adoption.workspace_id =
                            calendar_personal_projection_signal
                                .workspace_id
                          AND adoption.source_created_by_user_id =
                            calendar_personal_projection_signal
                                .created_by_user_id
                          AND adoption.status = 'ACTIVE'
                          AND selected.node_id =
                            calendar_personal_projection_signal
                                .source_node_id
                          AND app_actor_matches(
                              adoption.created_by_user_id))
                    OR EXISTS (
                        SELECT 1
                        FROM calendar_share share
                        WHERE share.plan_id =
                            calendar_personal_projection_signal.plan_id
                          AND share.workspace_id =
                            calendar_personal_projection_signal
                                .workspace_id
                          AND share.created_by_user_id =
                            calendar_personal_projection_signal
                                .created_by_user_id
                          AND share.status = 'ACTIVE'
                          AND app_actor_matches(
                              share.grantee_user_id)
                          AND (
                              share.scope_mode =
                                  'LIVE_WHOLE_PLAN'
                              OR (
                                  share.scope_mode =
                                      'SELECTED_NODES'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM calendar_share_scope_item item
                                      WHERE item.snapshot_id =
                                            share.current_scope_snapshot_id
                                        AND item.share_id = share.id
                                        AND item.target_kind = 'NODE'
                                        AND item.node_id =
                                            calendar_personal_projection_signal
                                                .source_node_id))
                              OR (
                                  share.scope_mode =
                                      'SELECTED_ACTIVITIES'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM calendar_share_scope_item item
                                      JOIN calendar_time_node node
                                        ON node.id =
                                            calendar_personal_projection_signal
                                                .source_node_id
                                       AND node.activity_id =
                                            item.activity_id
                                      WHERE item.snapshot_id =
                                            share.current_scope_snapshot_id
                                        AND item.share_id = share.id
                                        AND item.target_kind =
                                            'ACTIVITY'))))))));
CREATE POLICY rls_calendar_projection_signal_owner_insert
    ON calendar_personal_projection_signal FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_signal_authoritative_editor_insert
    ON calendar_personal_projection_signal FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND signal_kind = 'SOURCE_MUTATION'
        AND mutation_mode =
            'AUTHORITATIVE_AUTO_APPLY'
        AND recipient_user_id IS NULL
        AND EXISTS (
            SELECT 1
            FROM calendar_authoritative_mutation_audit mutation
            JOIN calendar_share_outbox outbox
              ON outbox.workspace_id = mutation.workspace_id
             AND outbox.authoritative_mutation_id =
                    mutation.mutation_id
            WHERE mutation.mutation_id =
                    calendar_personal_projection_signal
                        .authoritative_mutation_id
              AND mutation.plan_id =
                    calendar_personal_projection_signal.plan_id
              AND mutation.node_id =
                    calendar_personal_projection_signal
                        .source_node_id
              AND mutation.workspace_id =
                    calendar_personal_projection_signal.workspace_id
              AND mutation.created_by_user_id =
                    calendar_personal_projection_signal
                        .created_by_user_id
              AND mutation.current_target_revision =
                    calendar_personal_projection_signal
                        .source_revision
              AND app_actor_matches(mutation.editor_user_id)
              AND outbox.id =
                    calendar_personal_projection_signal
                        .source_outbox_id
              AND outbox.event_type =
                    'AUTHORITATIVE_MUTATION'
              AND outbox.created_by_user_id =
                    calendar_personal_projection_signal
                        .created_by_user_id));

ALTER TABLE calendar_personal_projection_snapshot
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_personal_projection_snapshot
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_projection_snapshot_actor_select
    ON calendar_personal_projection_snapshot FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_snapshot_actor_insert
    ON calendar_personal_projection_snapshot FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_snapshot_actor_update
    ON calendar_personal_projection_snapshot FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_personal_projection_node
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_personal_projection_node
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_projection_node_actor_select
    ON calendar_personal_projection_node FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_node_actor_insert
    ON calendar_personal_projection_node FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_skip_confirmation ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_skip_confirmation FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_skip_confirmation_actor_select
    ON calendar_skip_confirmation FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_skip_confirmation_actor_insert
    ON calendar_skip_confirmation FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND (
            (created_by_user_id = source_created_by_user_id
                AND share_id IS NULL)
            OR EXISTS (
                SELECT 1
                FROM calendar_share share
                WHERE share.id = calendar_skip_confirmation.share_id
                  AND share.plan_id =
                      calendar_skip_confirmation.plan_id
                  AND share.workspace_id =
                      calendar_skip_confirmation.workspace_id
                  AND share.created_by_user_id =
                      calendar_skip_confirmation
                          .source_created_by_user_id
                  AND share.grantee_user_id =
                      calendar_skip_confirmation.created_by_user_id
                  AND share.status = 'ACTIVE'
                  AND (
                      share.scope_mode = 'LIVE_WHOLE_PLAN'
                      OR (
                          calendar_skip_confirmation.target_scope =
                              'ACTIVITY'
                          AND share.scope_mode =
                              'SELECTED_ACTIVITIES'
                          AND EXISTS (
                              SELECT 1
                              FROM calendar_share_scope_item item
                              WHERE item.snapshot_id =
                                    share.current_scope_snapshot_id
                                AND item.share_id = share.id
                                AND item.activity_id =
                                    calendar_skip_confirmation
                                        .activity_id
                                AND item.target_kind =
                                    'ACTIVITY'))))));
CREATE POLICY rls_calendar_skip_confirmation_actor_update
    ON calendar_skip_confirmation FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND (
            (created_by_user_id = source_created_by_user_id
                AND share_id IS NULL)
            OR EXISTS (
                SELECT 1
                FROM calendar_share share
                WHERE share.id = calendar_skip_confirmation.share_id
                  AND share.plan_id =
                      calendar_skip_confirmation.plan_id
                  AND share.workspace_id =
                      calendar_skip_confirmation.workspace_id
                  AND share.created_by_user_id =
                      calendar_skip_confirmation
                          .source_created_by_user_id
                  AND share.grantee_user_id =
                      calendar_skip_confirmation.created_by_user_id
                  AND share.status = 'ACTIVE'
                  AND (
                      share.scope_mode = 'LIVE_WHOLE_PLAN'
                      OR (
                          calendar_skip_confirmation.target_scope =
                              'ACTIVITY'
                          AND share.scope_mode =
                              'SELECTED_ACTIVITIES'
                          AND EXISTS (
                              SELECT 1
                              FROM calendar_share_scope_item item
                              WHERE item.snapshot_id =
                                    share.current_scope_snapshot_id
                                AND item.share_id = share.id
                                AND item.activity_id =
                                    calendar_skip_confirmation
                                        .activity_id
                                AND item.target_kind =
                                    'ACTIVITY'))))));

ALTER TABLE calendar_projection_suppression
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_projection_suppression
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_projection_suppression_actor_select
    ON calendar_projection_suppression FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_suppression_actor_insert
    ON calendar_projection_suppression FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_projection_suppression_actor_update
    ON calendar_projection_suppression FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_authoritative_recipient_receipt
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_authoritative_recipient_receipt
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_authoritative_receipt_actor_select
    ON calendar_authoritative_recipient_receipt FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_authoritative_receipt_actor_insert
    ON calendar_authoritative_recipient_receipt FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_authoritative_receipt_actor_update
    ON calendar_authoritative_recipient_receipt FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_participation_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participation_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participation_outbox_actor_select
    ON calendar_participation_outbox FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_outbox_actor_insert
    ON calendar_participation_outbox FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_outbox_actor_update
    ON calendar_participation_outbox FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE FUNCTION reject_calendar_w9d_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_calendar_participation_history_append_only
BEFORE UPDATE OR DELETE ON calendar_participation_history
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE TRIGGER trg_calendar_projection_signal_append_only
BEFORE UPDATE OR DELETE ON calendar_personal_projection_signal
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE TRIGGER trg_calendar_projection_node_append_only
BEFORE UPDATE OR DELETE ON calendar_personal_projection_node
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_participation_policy_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'participation_policy', 'policy_revision', 'version',
            'operation_request_hash', 'operation_payload_hash',
            'updated_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'participation_policy', 'policy_revision', 'version',
            'operation_request_hash', 'operation_payload_hash',
            'updated_at'] THEN
        RAISE EXCEPTION
            'calendar participation policy identity is immutable';
    END IF;
    IF NEW.participation_policy = OLD.participation_policy
       OR NEW.policy_revision <> OLD.policy_revision + 1
       OR NEW.version <> OLD.version + 1
       OR NEW.operation_request_hash =
            OLD.operation_request_hash
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION
            'invalid calendar participation policy revision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_participation_policy_update_guard
BEFORE UPDATE ON calendar_participation_policy
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_participation_policy_update();

CREATE TRIGGER trg_calendar_participation_policy_no_delete
BEFORE DELETE ON calendar_participation_policy
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_participation_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'participation_state', 'participation_revision',
            'source_revision', 'operation_request_hash',
            'operation_payload_hash', 'updated_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'participation_state', 'participation_revision',
            'source_revision', 'operation_request_hash',
            'operation_payload_hash', 'updated_at'] THEN
        RAISE EXCEPTION 'calendar participation identity is immutable';
    END IF;
    IF NEW.participation_state = OLD.participation_state
       OR NEW.participation_revision <>
            OLD.participation_revision + 1
       OR NEW.source_revision < OLD.source_revision
       OR NEW.operation_request_hash =
            OLD.operation_request_hash
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid calendar participation revision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_participation_update_guard
BEFORE UPDATE ON calendar_participation
FOR EACH ROW EXECUTE FUNCTION guard_calendar_participation_update();

CREATE TRIGGER trg_calendar_participation_no_delete
BEFORE DELETE ON calendar_participation
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_routine_subscription_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'subscription_state', 'subscription_revision',
            'source_revision', 'operation_request_hash',
            'operation_payload_hash', 'updated_at',
            'unsubscribed_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'subscription_state', 'subscription_revision',
            'source_revision', 'operation_request_hash',
            'operation_payload_hash', 'updated_at',
            'unsubscribed_at'] THEN
        RAISE EXCEPTION
            'calendar routine subscription identity is immutable';
    END IF;
    IF NEW.subscription_state = OLD.subscription_state
       OR NEW.subscription_revision <>
            OLD.subscription_revision + 1
       OR NEW.source_revision < OLD.source_revision
       OR NEW.operation_request_hash =
            OLD.operation_request_hash
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION
            'invalid calendar routine subscription revision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_routine_subscription_update_guard
BEFORE UPDATE ON calendar_routine_subscription
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_routine_subscription_update();

CREATE TRIGGER trg_calendar_routine_subscription_no_delete
BEFORE DELETE ON calendar_routine_subscription
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_projection_snapshot_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - 'projection_status'
        IS DISTINCT FROM
        to_jsonb(OLD) - 'projection_status'
       OR OLD.projection_status = 'SUPERSEDED'
       OR NEW.projection_status <> 'SUPERSEDED' THEN
        RAISE EXCEPTION
            'calendar personal projection snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_projection_snapshot_update_guard
BEFORE UPDATE ON calendar_personal_projection_snapshot
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_projection_snapshot_update();

CREATE TRIGGER trg_calendar_projection_snapshot_no_delete
BEFORE DELETE ON calendar_personal_projection_snapshot
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_skip_confirmation_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'confirmation_request_hash',
            'confirmation_payload_hash', 'consumed_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'confirmation_request_hash',
            'confirmation_payload_hash', 'consumed_at']
       OR OLD.consumed_at IS NOT NULL
       OR NEW.consumed_at IS NULL THEN
        RAISE EXCEPTION
            'skip preview may only be consumed once';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_skip_confirmation_consume_guard
BEFORE UPDATE ON calendar_skip_confirmation
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_skip_confirmation_update();

CREATE TRIGGER trg_calendar_skip_confirmation_no_delete
BEFORE DELETE ON calendar_skip_confirmation
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_projection_suppression_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'status', 'suppression_revision',
            'updated_at', 'released_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'status', 'suppression_revision',
            'updated_at', 'released_at']
       OR OLD.status <> 'ACTIVE'
       OR NEW.status <> 'RELEASED'
       OR NEW.suppression_revision <>
            OLD.suppression_revision + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION
            'invalid calendar projection suppression lifecycle';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_projection_suppression_update_guard
BEFORE UPDATE ON calendar_projection_suppression
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_projection_suppression_update();

CREATE TRIGGER trg_calendar_projection_suppression_no_delete
BEFORE DELETE ON calendar_projection_suppression
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_authoritative_receipt_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'delivery_status', 'delivery_attempt_count',
            'next_delivery_attempt_at', 'delivered_at',
            'last_delivery_failure', 'acknowledgement_status',
            'acknowledged_at', 'receipt_revision', 'updated_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'delivery_status', 'delivery_attempt_count',
            'next_delivery_attempt_at', 'delivered_at',
            'last_delivery_failure', 'acknowledgement_status',
            'acknowledged_at', 'receipt_revision', 'updated_at']
       OR NEW.receipt_revision <> OLD.receipt_revision + 1
       OR NEW.delivery_attempt_count <
            OLD.delivery_attempt_count
       OR NEW.updated_at < OLD.updated_at
       OR (OLD.acknowledgement_status = 'ACKNOWLEDGED'
            AND NEW.acknowledgement_status <>
                'ACKNOWLEDGED')
       OR (OLD.acknowledgement_status = 'NOT_REQUIRED'
            AND NEW.acknowledgement_status <>
                'NOT_REQUIRED')
       OR (
            OLD.delivery_status = 'SENT'
            AND (
                NEW.delivery_status <> OLD.delivery_status
                OR NEW.delivery_attempt_count <>
                    OLD.delivery_attempt_count
                OR NEW.next_delivery_attempt_at
                    IS DISTINCT FROM
                        OLD.next_delivery_attempt_at
                OR NEW.delivered_at IS DISTINCT FROM
                    OLD.delivered_at
                OR NEW.last_delivery_failure
                    IS DISTINCT FROM
                        OLD.last_delivery_failure)) THEN
        RAISE EXCEPTION
            'invalid calendar authoritative receipt lifecycle';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_authoritative_receipt_update_guard
BEFORE UPDATE ON calendar_authoritative_recipient_receipt
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_authoritative_receipt_update();

CREATE TRIGGER trg_calendar_authoritative_receipt_no_delete
BEFORE DELETE ON calendar_authoritative_recipient_receipt
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION guard_calendar_participation_outbox_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF to_jsonb(NEW) - ARRAY[
            'delivery_status', 'delivery_attempt_count',
            'next_delivery_attempt_at', 'delivered_at',
            'last_delivery_failure', 'updated_at']
        IS DISTINCT FROM
        to_jsonb(OLD) - ARRAY[
            'delivery_status', 'delivery_attempt_count',
            'next_delivery_attempt_at', 'delivered_at',
            'last_delivery_failure', 'updated_at']
       OR NEW.delivery_attempt_count <
            OLD.delivery_attempt_count
       OR NEW.updated_at < OLD.updated_at
       OR OLD.delivery_status = 'SENT' THEN
        RAISE EXCEPTION
            'invalid calendar participation outbox lifecycle';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_participation_outbox_update_guard
BEFORE UPDATE ON calendar_participation_outbox
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_participation_outbox_update();

CREATE TRIGGER trg_calendar_participation_outbox_no_delete
BEFORE DELETE ON calendar_participation_outbox
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE TABLE calendar_participation_request_receipt (
    id UUID PRIMARY KEY,
    operation_kind VARCHAR(30) NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    semantic_identity_hash VARCHAR(64) NOT NULL,
    participation_id UUID,
    routine_subscription_id UUID,
    participation_policy_id UUID,
    skip_confirmation_id UUID,
    plan_id UUID NOT NULL,
    activity_id UUID,
    result_state VARCHAR(30),
    result_revision BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participation_request_receipt_key
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_participation_request_result
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_watch_request_result
        FOREIGN KEY (
            routine_subscription_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_routine_subscription (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_policy_request_result
        FOREIGN KEY (
            participation_policy_id, plan_id, workspace_id,
            created_by_user_id)
        REFERENCES calendar_participation_policy (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_skip_request_result
        FOREIGN KEY (
            skip_confirmation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_skip_confirmation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_participation_request_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_participation_request_shape CHECK (
        (operation_kind = 'PARTICIPATION'
            AND participation_id IS NOT NULL
            AND routine_subscription_id IS NULL
            AND participation_policy_id IS NULL
            AND skip_confirmation_id IS NULL)
        OR (operation_kind IN (
                'WATCH_SUBSCRIBE', 'WATCH_UNSUBSCRIBE')
            AND participation_id IS NULL
            AND routine_subscription_id IS NOT NULL
            AND participation_policy_id IS NULL
            AND skip_confirmation_id IS NULL)
        OR (operation_kind = 'POLICY_CHANGE'
            AND participation_id IS NULL
            AND routine_subscription_id IS NULL
            AND participation_policy_id IS NOT NULL
            AND skip_confirmation_id IS NULL
            AND created_by_user_id = source_created_by_user_id)
        OR (operation_kind IN (
                'SKIP_PREVIEW', 'SKIP_CONFIRM')
            AND participation_id IS NULL
            AND routine_subscription_id IS NULL
            AND participation_policy_id IS NULL
            AND skip_confirmation_id IS NOT NULL)),
    CONSTRAINT chk_calendar_participation_request_result_state CHECK (
        (operation_kind = 'PARTICIPATION'
            AND result_state IN (
                'TENTATIVE', 'COMMITTED', 'DECLINED', 'OPTED_OUT'))
        OR (operation_kind = 'WATCH_SUBSCRIBE'
            AND result_state = 'WATCHING')
        OR (operation_kind = 'WATCH_UNSUBSCRIBE'
            AND result_state = 'UNSUBSCRIBED')
        OR (operation_kind = 'POLICY_CHANGE'
            AND result_state IN (
                'OPTIONAL', 'RECOMMENDED', 'REQUIRED'))
        OR (operation_kind IN (
                'SKIP_PREVIEW', 'SKIP_CONFIRM')
            AND result_state IS NULL)),
    CONSTRAINT chk_calendar_participation_request_revision
        CHECK (result_revision >= 0),
    CONSTRAINT chk_calendar_participation_request_hashes CHECK (
        operation_request_hash ~ '^[0-9a-f]{64}$'
        AND operation_payload_hash ~ '^[0-9a-f]{64}$'
        AND semantic_identity_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_calendar_participation_request_semantic
    ON calendar_participation_request_receipt (
        workspace_id, created_by_user_id,
        operation_kind, semantic_identity_hash);

ALTER TABLE calendar_participation_request_receipt
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participation_request_receipt
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participation_request_actor_select
    ON calendar_participation_request_receipt FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participation_request_actor_insert
    ON calendar_participation_request_receipt FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE TRIGGER trg_calendar_participation_request_append_only
BEFORE UPDATE OR DELETE ON calendar_participation_request_receipt
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_w9d_append_only_mutation();

CREATE FUNCTION enforce_calendar_participation_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_participation calendar_participation%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'calendar_participation' THEN
        checked_participation := NEW;
    ELSE
        SELECT participation.*
        INTO checked_participation
        FROM calendar_participation participation
        WHERE participation.id = NEW.participation_id
          AND participation.workspace_id = NEW.workspace_id
          AND participation.created_by_user_id =
              NEW.created_by_user_id;
    END IF;

    IF checked_participation.id IS NULL
       OR NOT EXISTS (
            SELECT 1
            FROM calendar_participation_history history
            WHERE history.participation_id =
                    checked_participation.id
              AND history.plan_id =
                    checked_participation.plan_id
              AND history.activity_id IS NOT DISTINCT FROM
                    checked_participation.activity_id
              AND history.target_scope =
                    checked_participation.target_scope
              AND history.current_state =
                    checked_participation.participation_state
              AND history.participation_revision =
                    checked_participation.participation_revision
              AND history.source_revision =
                    checked_participation.source_revision
              AND history.operation_request_hash =
                    checked_participation.operation_request_hash
              AND history.operation_payload_hash =
                    checked_participation.operation_payload_hash
              AND history.workspace_id =
                    checked_participation.workspace_id
              AND history.created_by_user_id =
                    checked_participation.created_by_user_id
              AND history.source_created_by_user_id =
                    checked_participation.source_created_by_user_id) THEN
        RAISE EXCEPTION
            'participation current revision requires exact history';
    END IF;

    IF checked_participation.participation_revision = 1 THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_participation_history history
            WHERE history.participation_id =
                    checked_participation.id
              AND history.participation_revision = 1
              AND history.event_type =
                    'PARTICIPATION_DECLARED'
              AND history.previous_state IS NULL) THEN
            RAISE EXCEPTION
                'initial participation history is invalid';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM calendar_participation_history current_history
        JOIN calendar_participation_history previous_history
          ON previous_history.participation_id =
                current_history.participation_id
         AND previous_history.participation_revision =
                current_history.participation_revision - 1
        WHERE current_history.participation_id =
                checked_participation.id
          AND current_history.participation_revision =
                checked_participation.participation_revision
          AND current_history.event_type = 'STATE_CHANGED'
          AND current_history.previous_state =
                previous_history.current_state) THEN
        RAISE EXCEPTION
            'participation history chain is invalid';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_participation_history_required
AFTER INSERT OR UPDATE ON calendar_participation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_participation_history();

CREATE CONSTRAINT TRIGGER trg_calendar_participation_history_matches
AFTER INSERT ON calendar_participation_history
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_participation_history();

CREATE FUNCTION enforce_calendar_projection_signal_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.signal_kind = 'SOURCE_MUTATION'
       AND NEW.mutation_mode =
            'AUTHORITATIVE_AUTO_APPLY' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_share_outbox outbox
            JOIN calendar_authoritative_mutation_audit mutation
              ON mutation.workspace_id = outbox.workspace_id
             AND mutation.mutation_id =
                    outbox.authoritative_mutation_id
            WHERE outbox.id = NEW.source_outbox_id
              AND outbox.event_type = 'AUTHORITATIVE_MUTATION'
              AND outbox.authoritative_mutation_id =
                    NEW.authoritative_mutation_id
              AND outbox.workspace_id = NEW.workspace_id
              AND outbox.created_by_user_id =
                    NEW.created_by_user_id
              AND mutation.plan_id = NEW.plan_id
              AND mutation.node_id = NEW.source_node_id
              AND mutation.current_target_revision =
                    NEW.source_revision
              AND mutation.created_by_user_id =
                    NEW.created_by_user_id) THEN
            RAISE EXCEPTION
                'authoritative projection signal source mismatch';
        END IF;
    ELSIF NEW.signal_kind = 'SOURCE_MUTATION'
          AND NEW.mutation_mode = 'GENERAL_REVIEW' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_time_node node
            WHERE node.id = NEW.source_node_id
              AND node.plan_id = NEW.plan_id
              AND node.revision = NEW.source_revision
              AND node.workspace_id = NEW.workspace_id
              AND node.created_by_user_id =
                    NEW.created_by_user_id) THEN
            RAISE EXCEPTION
                'owner projection signal source mismatch';
        END IF;
    ELSIF NEW.signal_kind = 'SHARE_REVOKED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_share_outbox outbox
            JOIN calendar_share share
              ON share.id = outbox.share_id
             AND share.workspace_id = outbox.workspace_id
             AND share.created_by_user_id =
                    outbox.created_by_user_id
            WHERE outbox.id = NEW.source_outbox_id
              AND outbox.event_type = 'SHARE_REVOKED'
              AND outbox.share_id = NEW.share_id
              AND outbox.grantee_user_id =
                    NEW.recipient_user_id
              AND outbox.workspace_id = NEW.workspace_id
              AND outbox.created_by_user_id =
                    NEW.created_by_user_id
              AND share.plan_id = NEW.plan_id
              AND share.grantee_user_id =
                    NEW.recipient_user_id
              AND share.share_revision = NEW.source_revision
              AND share.status = 'REVOKED') THEN
            RAISE EXCEPTION
                'share revoke projection signal source mismatch';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_projection_signal_source
AFTER INSERT ON calendar_personal_projection_signal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_projection_signal_source();

CREATE FUNCTION enforce_calendar_skip_suppression()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_confirmation calendar_skip_confirmation%ROWTYPE;
    checked_suppression calendar_projection_suppression%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'calendar_skip_confirmation' THEN
        checked_confirmation := NEW;
        IF NEW.consumed_at IS NULL THEN
            RETURN NULL;
        END IF;
        SELECT suppression.*
        INTO checked_suppression
        FROM calendar_projection_suppression suppression
        WHERE suppression.confirmation_id = NEW.id
          AND suppression.workspace_id = NEW.workspace_id
          AND suppression.created_by_user_id =
              NEW.created_by_user_id;
    ELSE
        checked_suppression := NEW;
        SELECT confirmation.*
        INTO checked_confirmation
        FROM calendar_skip_confirmation confirmation
        WHERE confirmation.id = NEW.confirmation_id
          AND confirmation.workspace_id = NEW.workspace_id
          AND confirmation.created_by_user_id =
              NEW.created_by_user_id;
    END IF;

    IF checked_confirmation.id IS NULL
       OR checked_suppression.id IS NULL
       OR checked_confirmation.consumed_at IS NULL
       OR checked_confirmation.adoption_id
            IS DISTINCT FROM checked_suppression.adoption_id
       OR checked_confirmation.participation_id
            IS DISTINCT FROM checked_suppression.participation_id
       OR checked_confirmation.plan_id <>
            checked_suppression.plan_id
       OR checked_confirmation.activity_id
            IS DISTINCT FROM checked_suppression.activity_id
       OR checked_confirmation.target_scope <>
            checked_suppression.target_scope
       OR checked_confirmation.expected_participation_revision
            IS DISTINCT FROM
                checked_suppression.participation_revision
       OR checked_confirmation.expected_projection_revision
            IS DISTINCT FROM
                checked_suppression.projection_revision
       OR checked_confirmation.expected_source_revision <>
            checked_suppression.source_revision
       OR checked_confirmation.source_created_by_user_id <>
            checked_suppression.source_created_by_user_id THEN
        RAISE EXCEPTION
            'consumed skip preview requires exact suppression';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM calendar_participation_outbox outbox
        WHERE outbox.event_type = 'SKIP_CONFIRMED'
          AND outbox.suppression_id = checked_suppression.id
          AND outbox.source_revision =
                checked_suppression.source_revision
          AND outbox.workspace_id =
                checked_suppression.workspace_id
          AND outbox.created_by_user_id =
                checked_suppression.created_by_user_id) THEN
        RAISE EXCEPTION
            'consumed skip requires durable notification outbox';
    END IF;

    IF checked_confirmation.participation_id IS NOT NULL
       AND NOT EXISTS (
            SELECT 1
            FROM calendar_participation participation
            WHERE participation.id =
                    checked_confirmation.participation_id
              AND participation.participation_revision =
                    checked_confirmation
                        .expected_participation_revision
              AND participation.workspace_id =
                    checked_confirmation.workspace_id
              AND participation.created_by_user_id =
                    checked_confirmation.created_by_user_id) THEN
        RAISE EXCEPTION
            'skip confirmation participation revision is stale';
    END IF;

    IF checked_confirmation.adoption_id IS NOT NULL
       AND NOT EXISTS (
            SELECT 1
            FROM calendar_personal_projection_snapshot snapshot
            WHERE snapshot.adoption_id =
                    checked_confirmation.adoption_id
              AND snapshot.projection_revision =
                    checked_confirmation
                        .expected_projection_revision
              AND snapshot.projection_status <> 'SUPERSEDED'
              AND snapshot.workspace_id =
                    checked_confirmation.workspace_id
              AND snapshot.created_by_user_id =
                    checked_confirmation.created_by_user_id) THEN
        RAISE EXCEPTION
            'skip confirmation projection revision is stale';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_skip_confirmation_suppression
AFTER INSERT OR UPDATE ON calendar_skip_confirmation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_skip_suppression();

CREATE CONSTRAINT TRIGGER trg_calendar_projection_suppression_confirmation
AFTER INSERT ON calendar_projection_suppression
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_skip_suppression();

CREATE FUNCTION require_calendar_participation_exit_confirmation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_participation calendar_participation%ROWTYPE;
BEGIN
    IF NEW.current_state NOT IN ('DECLINED', 'OPTED_OUT') THEN
        RETURN NULL;
    END IF;

    SELECT participation.*
    INTO checked_participation
    FROM calendar_participation participation
    WHERE participation.id = NEW.participation_id
      AND participation.plan_id = NEW.plan_id
      AND participation.workspace_id = NEW.workspace_id
      AND participation.created_by_user_id =
            NEW.created_by_user_id
      AND participation.source_created_by_user_id =
            NEW.source_created_by_user_id;

    IF checked_participation.id IS NULL THEN
        RAISE EXCEPTION
            'calendar participation exit requires its current state';
    END IF;

    IF NEW.previous_state = 'COMMITTED'
       OR checked_participation.participation_policy = 'REQUIRED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_projection_suppression suppression
            JOIN calendar_skip_confirmation confirmation
              ON confirmation.id = suppression.confirmation_id
             AND confirmation.workspace_id =
                    suppression.workspace_id
             AND confirmation.created_by_user_id =
                    suppression.created_by_user_id
            WHERE suppression.participation_id =
                    NEW.participation_id
              AND suppression.plan_id = NEW.plan_id
              AND suppression.activity_id IS NOT DISTINCT FROM
                    NEW.activity_id
              AND suppression.target_scope = NEW.target_scope
              AND suppression.participation_revision =
                    NEW.participation_revision
              AND suppression.source_revision =
                    NEW.source_revision
              AND suppression.status = 'ACTIVE'
              AND suppression.workspace_id = NEW.workspace_id
              AND suppression.created_by_user_id =
                    NEW.created_by_user_id
              AND suppression.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND confirmation.consumed_at IS NOT NULL
              AND confirmation.confirmation_request_hash =
                    NEW.operation_request_hash) THEN
            RAISE EXCEPTION
                'committed or required participation exit requires exact confirmed suppression';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER
    trg_calendar_participation_exit_confirmation
AFTER INSERT ON calendar_participation_history
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    require_calendar_participation_exit_confirmation();

CREATE FUNCTION enforce_calendar_authoritative_receipt_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.disposition = 'AUTO_APPLIED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_participation participation
            JOIN calendar_personal_projection_snapshot snapshot
              ON snapshot.id = NEW.personal_snapshot_id
             AND snapshot.participation_id = participation.id
             AND snapshot.workspace_id =
                    participation.workspace_id
             AND snapshot.created_by_user_id =
                    participation.created_by_user_id
            WHERE participation.id = NEW.participation_id
              AND participation.participation_state = 'COMMITTED'
              AND participation.plan_id = NEW.plan_id
              AND participation.workspace_id = NEW.workspace_id
              AND participation.created_by_user_id =
                    NEW.created_by_user_id
              AND snapshot.source_signal_id = NEW.signal_id
              AND snapshot.projection_status = 'ACTIVE'
              AND EXISTS (
                    SELECT 1
                    FROM calendar_personal_projection_signal signal
                    WHERE signal.id = NEW.signal_id
                      AND signal.mutation_mode =
                            'AUTHORITATIVE_AUTO_APPLY')) THEN
            RAISE EXCEPTION
                'auto-applied receipt requires committed projection';
        END IF;
    ELSIF NEW.disposition = 'REVIEW_REQUIRED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_participation participation
            WHERE participation.id = NEW.participation_id
              AND (
                    participation.participation_state =
                        'TENTATIVE'
                    OR (
                        participation.participation_state =
                            'COMMITTED'
                        AND EXISTS (
                            SELECT 1
                            FROM calendar_personal_projection_signal signal
                            WHERE signal.id = NEW.signal_id
                              AND signal.mutation_mode =
                                    'GENERAL_REVIEW')))
              AND participation.plan_id = NEW.plan_id
              AND participation.workspace_id = NEW.workspace_id
              AND participation.created_by_user_id =
                    NEW.created_by_user_id) THEN
            RAISE EXCEPTION
                'review receipt requires an eligible participation';
        END IF;
        IF NEW.personal_snapshot_id IS NOT NULL
           AND NOT EXISTS (
                SELECT 1
                FROM calendar_personal_projection_snapshot snapshot
                WHERE snapshot.id = NEW.personal_snapshot_id
                  AND snapshot.source_signal_id = NEW.signal_id
                  AND snapshot.projection_status =
                        'REVIEW_REQUIRED'
                  AND snapshot.workspace_id = NEW.workspace_id
                  AND snapshot.created_by_user_id =
                        NEW.created_by_user_id) THEN
            RAISE EXCEPTION
                'review receipt snapshot must match source signal';
        END IF;
    ELSIF NEW.disposition = 'ROUTINE_UPDATE' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_routine_subscription subscription
            WHERE subscription.id = NEW.routine_subscription_id
              AND subscription.subscription_state = 'WATCHING'
              AND subscription.plan_id = NEW.plan_id
              AND subscription.workspace_id = NEW.workspace_id
              AND subscription.created_by_user_id =
                    NEW.created_by_user_id) THEN
            RAISE EXCEPTION
                'routine receipt requires active private watch';
        END IF;
    ELSIF NEW.disposition = 'SOURCE_ACCESS_REVOKED'
          AND NEW.personal_snapshot_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
            FROM calendar_personal_projection_snapshot snapshot
            WHERE snapshot.id = NEW.personal_snapshot_id
              AND snapshot.source_signal_id = NEW.signal_id
              AND snapshot.projection_status =
                    'RETAINED_NO_SOURCE_ACCESS'
              AND snapshot.workspace_id = NEW.workspace_id
              AND snapshot.created_by_user_id =
                    NEW.created_by_user_id) THEN
        RAISE EXCEPTION
            'share revoke receipt snapshot must be retained';
    ELSIF NEW.disposition = 'SOURCE_ACCESS_REVOKED'
          AND EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.plan_id = NEW.plan_id
              AND share.workspace_id = NEW.workspace_id
              AND share.created_by_user_id =
                    NEW.source_created_by_user_id
              AND share.grantee_user_id =
                    NEW.created_by_user_id
              AND share.status = 'ACTIVE') THEN
        RAISE EXCEPTION
            'source access revoke receipt cannot hide additive access';
    ELSIF NEW.disposition = 'ACCESS_RETAINED'
          AND NOT EXISTS (
            SELECT 1
            FROM calendar_share share
            WHERE share.plan_id = NEW.plan_id
              AND share.workspace_id = NEW.workspace_id
              AND share.created_by_user_id =
                    NEW.source_created_by_user_id
              AND share.grantee_user_id =
                    NEW.created_by_user_id
              AND share.status = 'ACTIVE') THEN
        RAISE EXCEPTION
            'access retained receipt requires effective access';
    ELSIF NEW.disposition = 'SUPPRESSED'
          AND NOT EXISTS (
            SELECT 1
            FROM calendar_participation participation
            WHERE participation.id = NEW.participation_id
              AND participation.participation_state IN (
                    'DECLINED', 'OPTED_OUT')
              AND participation.plan_id = NEW.plan_id
              AND participation.workspace_id = NEW.workspace_id
              AND participation.created_by_user_id =
                    NEW.created_by_user_id) THEN
        RAISE EXCEPTION
            'suppressed receipt requires declined or opted-out state';
    ELSIF NEW.disposition = 'NO_ACTION'
          AND (
            EXISTS (
                SELECT 1
                FROM calendar_participation participation
                WHERE participation.plan_id = NEW.plan_id
                  AND participation.workspace_id =
                        NEW.workspace_id
                  AND participation.source_created_by_user_id =
                        NEW.source_created_by_user_id
                  AND participation.created_by_user_id =
                        NEW.created_by_user_id)
            OR EXISTS (
                SELECT 1
                FROM calendar_routine_subscription subscription
                WHERE subscription.plan_id = NEW.plan_id
                  AND subscription.workspace_id =
                        NEW.workspace_id
                  AND subscription.source_created_by_user_id =
                        NEW.source_created_by_user_id
                  AND subscription.created_by_user_id =
                        NEW.created_by_user_id
                  AND subscription.subscription_state =
                        'WATCHING')) THEN
        RAISE EXCEPTION
            'no-action receipt has an applicable private projection';
    END IF;

    IF NEW.disposition IN (
            'AUTO_APPLIED', 'REVIEW_REQUIRED',
            'ROUTINE_UPDATE', 'SOURCE_ACCESS_REVOKED')
       AND NOT EXISTS (
        SELECT 1
        FROM calendar_participation_outbox outbox
        WHERE outbox.receipt_id = NEW.id
          AND outbox.signal_id = NEW.signal_id
          AND outbox.workspace_id = NEW.workspace_id
          AND outbox.created_by_user_id =
                NEW.created_by_user_id
          AND (
              (NEW.disposition IN (
                    'AUTO_APPLIED', 'REVIEW_REQUIRED')
                  AND outbox.event_type IN (
                      'REVIEW_REQUIRED_UPDATE',
                      'MANDATORY_UPDATE'))
              OR (NEW.disposition = 'ROUTINE_UPDATE'
                  AND outbox.event_type = 'ROUTINE_UPDATE')
              OR (NEW.disposition = 'SOURCE_ACCESS_REVOKED'
                  AND outbox.event_type =
                      'SHARE_ACCESS_REVOKED'))) THEN
        RAISE EXCEPTION
            'recipient receipt requires durable notification outbox';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_authoritative_receipt_context
AFTER INSERT ON calendar_authoritative_recipient_receipt
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_authoritative_receipt_context();

CREATE FUNCTION enforce_calendar_participation_outbox_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_type = 'SKIP_CONFIRMED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_projection_suppression suppression
            JOIN calendar_skip_confirmation confirmation
              ON confirmation.id = suppression.confirmation_id
             AND confirmation.workspace_id =
                    suppression.workspace_id
             AND confirmation.created_by_user_id =
                    suppression.created_by_user_id
            WHERE suppression.id = NEW.suppression_id
              AND suppression.workspace_id = NEW.workspace_id
              AND suppression.created_by_user_id =
                    NEW.created_by_user_id
              AND suppression.source_revision =
                    NEW.source_revision
              AND confirmation.consumed_at IS NOT NULL) THEN
            RAISE EXCEPTION
                'skip outbox requires consumed exact suppression';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM calendar_authoritative_recipient_receipt receipt
            JOIN calendar_personal_projection_signal signal
              ON signal.id = receipt.signal_id
             AND signal.workspace_id = receipt.workspace_id
             AND signal.created_by_user_id =
                    receipt.source_created_by_user_id
            WHERE receipt.id = NEW.receipt_id
              AND receipt.signal_id = NEW.signal_id
              AND receipt.plan_id = NEW.plan_id
              AND receipt.workspace_id = NEW.workspace_id
              AND receipt.created_by_user_id =
                    NEW.created_by_user_id
              AND receipt.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND signal.source_revision =
                    NEW.source_revision
              AND (
                  (NEW.event_type IN (
                      'REVIEW_REQUIRED_UPDATE', 'MANDATORY_UPDATE')
                      AND receipt.disposition IN (
                          'AUTO_APPLIED', 'REVIEW_REQUIRED'))
                  OR (NEW.event_type = 'ROUTINE_UPDATE'
                      AND receipt.disposition = 'ROUTINE_UPDATE')
                  OR (NEW.event_type = 'SHARE_ACCESS_REVOKED'
                      AND receipt.disposition =
                          'SOURCE_ACCESS_REVOKED'))) THEN
            RAISE EXCEPTION
                'participation outbox receipt context mismatch';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_calendar_participation_outbox_context
AFTER INSERT ON calendar_participation_outbox
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    enforce_calendar_participation_outbox_context();

DROP TRIGGER trg_calendar_adoption_node_immutable
    ON calendar_adoption_node;

CREATE POLICY rls_calendar_adoption_node_actor_update
    ON calendar_adoption_node FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

CREATE OR REPLACE FUNCTION reject_calendar_adoption_node_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    visible_source_revision BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'calendar_adoption_node deletion is forbidden';
    END IF;
    IF OLD.adoption_id IS DISTINCT FROM NEW.adoption_id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.node_id IS DISTINCT FROM NEW.node_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.created_by_user_id IS DISTINCT FROM
            NEW.created_by_user_id
       OR OLD.source_created_by_user_id IS DISTINCT FROM
            NEW.source_created_by_user_id
       OR NOT app_workspace_matches(NEW.workspace_id)
       OR NOT app_actor_matches(NEW.created_by_user_id)
       OR NEW.node_revision <= OLD.node_revision
       OR NOT EXISTS (
            SELECT 1
            FROM calendar_adoption adoption
            WHERE adoption.id = NEW.adoption_id
              AND adoption.plan_id = NEW.plan_id
              AND adoption.workspace_id = NEW.workspace_id
              AND adoption.created_by_user_id =
                    NEW.created_by_user_id
              AND adoption.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND adoption.status = 'ACTIVE') THEN
        RAISE EXCEPTION
            'calendar_adoption_node identity is immutable';
    END IF;

    SELECT node.revision
    INTO visible_source_revision
    FROM calendar_time_node node
    WHERE node.id = NEW.node_id
      AND node.plan_id = NEW.plan_id
      AND node.workspace_id = NEW.workspace_id
      AND node.created_by_user_id =
            NEW.source_created_by_user_id;

    IF visible_source_revision IS NULL
       OR NEW.node_revision <> visible_source_revision THEN
        RAISE EXCEPTION
            'calendar_adoption_node revision must match visible source';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_adoption_node_revision_guard
BEFORE UPDATE OR DELETE ON calendar_adoption_node
FOR EACH ROW EXECUTE FUNCTION
    reject_calendar_adoption_node_mutation();

ALTER TABLE calendar_personal_projection_node
    ADD CONSTRAINT uq_calendar_projection_node_reminder_target
        UNIQUE (
            snapshot_id, source_node_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id);

ALTER TABLE calendar_reminder_rule
    ADD COLUMN source_created_by_user_id UUID,
    ADD COLUMN personal_projection_snapshot_id UUID;

ALTER TABLE calendar_reminder_occurrence
    ADD COLUMN source_created_by_user_id UUID;

-- Existing reminder rows are source-owned. Suspend reminder RLS only for
-- this transaction-bounded ownership backfill and restore FORCE below.
ALTER TABLE calendar_reminder_occurrence
    NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_occurrence
    DISABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_rule
    NO FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_rule
    DISABLE ROW LEVEL SECURITY;

UPDATE calendar_reminder_rule
SET source_created_by_user_id = created_by_user_id;

UPDATE calendar_reminder_occurrence
SET source_created_by_user_id = created_by_user_id;

ALTER TABLE calendar_reminder_rule
    ALTER COLUMN source_created_by_user_id SET NOT NULL,
    DROP CONSTRAINT fk_calendar_reminder_rule_plan,
    DROP CONSTRAINT fk_calendar_reminder_rule_node,
    ADD CONSTRAINT uq_calendar_reminder_rule_source_identity
        UNIQUE (
            id, plan_id, node_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    ADD CONSTRAINT fk_calendar_reminder_rule_source_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    ADD CONSTRAINT fk_calendar_reminder_rule_source_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id),
    ADD CONSTRAINT fk_calendar_reminder_rule_projection_node
        FOREIGN KEY (
            personal_projection_snapshot_id, node_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id)
        REFERENCES calendar_personal_projection_node (
            snapshot_id, source_node_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id),
    ADD CONSTRAINT chk_calendar_reminder_rule_projection_owner CHECK (
        (owner_kind = 'SHARED_TEMPLATE'
            AND created_by_user_id = source_created_by_user_id
            AND personal_projection_snapshot_id IS NULL)
        OR (owner_kind = 'PERSONAL'
            AND (
                created_by_user_id = source_created_by_user_id
                OR (
                    created_by_user_id <>
                        source_created_by_user_id
                    AND personal_projection_snapshot_id
                        IS NOT NULL))));

ALTER TABLE calendar_reminder_occurrence
    ALTER COLUMN source_created_by_user_id SET NOT NULL,
    DROP CONSTRAINT fk_calendar_reminder_occurrence_plan,
    DROP CONSTRAINT fk_calendar_reminder_occurrence_node,
    ADD CONSTRAINT fk_calendar_reminder_occurrence_source_rule
        FOREIGN KEY (
            rule_id, plan_id, node_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_reminder_rule (
            id, plan_id, node_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    ADD CONSTRAINT fk_calendar_reminder_occurrence_source_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    ADD CONSTRAINT fk_calendar_reminder_occurrence_source_node
        FOREIGN KEY (
            node_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_time_node (
            id, plan_id, workspace_id, created_by_user_id);

ALTER TABLE calendar_reminder_rule
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_rule
    FORCE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_occurrence
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_reminder_occurrence
    FORCE ROW LEVEL SECURITY;

CREATE FUNCTION guard_calendar_personal_reminder_projection_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.source_created_by_user_id IS DISTINCT FROM
            NEW.source_created_by_user_id
       OR OLD.personal_projection_snapshot_id IS DISTINCT FROM
            NEW.personal_projection_snapshot_id THEN
        RAISE EXCEPTION
            'calendar personal reminder projection target is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_personal_reminder_projection_target
BEFORE UPDATE ON calendar_reminder_rule
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_personal_reminder_projection_target();

CREATE FUNCTION guard_calendar_reminder_occurrence_source_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT'
       AND OLD.source_created_by_user_id IS DISTINCT FROM
            NEW.source_created_by_user_id THEN
        RAISE EXCEPTION
            'calendar reminder occurrence source owner is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_reminder_occurrence_source_owner
BEFORE INSERT OR UPDATE ON calendar_reminder_occurrence
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_reminder_occurrence_source_owner();
