ALTER TABLE calendar_share
    DROP CONSTRAINT fk_calendar_share_member;

CREATE TABLE calendar_plan_ownership (
    plan_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_plan_ownership_source
        UNIQUE (
            plan_id, workspace_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_plan_ownership_effective
        UNIQUE (
            plan_id, workspace_id,
            source_created_by_user_id, owner_user_id),
    CONSTRAINT fk_calendar_plan_ownership_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_plan_ownership_owner
        FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_calendar_plan_ownership_revision
        CHECK (ownership_revision > 0)
);

INSERT INTO calendar_plan_ownership (
    plan_id, owner_user_id, ownership_revision,
    created_at, updated_at, workspace_id,
    source_created_by_user_id)
SELECT plan.id, plan.created_by_user_id, 1,
       plan.created_at, plan.updated_at, plan.workspace_id,
       plan.created_by_user_id
FROM calendar_plan plan
JOIN workspace_member member
  ON member.workspace_id = plan.workspace_id
 AND member.user_id = plan.created_by_user_id
ON CONFLICT (plan_id) DO NOTHING;

CREATE FUNCTION create_calendar_plan_initial_ownership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO calendar_plan_ownership (
        plan_id, owner_user_id, ownership_revision,
        created_at, updated_at, workspace_id,
        source_created_by_user_id)
    VALUES (
        NEW.id, NEW.created_by_user_id, 1,
        NEW.created_at, NEW.updated_at, NEW.workspace_id,
        NEW.created_by_user_id);
    INSERT INTO calendar_active_owner_membership_guard (
        plan_id, workspace_id, owner_user_id,
        source_created_by_user_id)
    SELECT
        NEW.id, NEW.workspace_id, NEW.created_by_user_id,
        NEW.created_by_user_id
    WHERE EXISTS (
        SELECT 1
        FROM workspace_member member
        WHERE member.workspace_id = NEW.workspace_id
          AND member.user_id = NEW.created_by_user_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_plan_initial_ownership
AFTER INSERT ON calendar_plan
FOR EACH ROW EXECUTE FUNCTION create_calendar_plan_initial_ownership();

CREATE FUNCTION guard_calendar_plan_ownership_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.source_created_by_user_id IS DISTINCT FROM
            NEW.source_created_by_user_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR NEW.ownership_revision <> OLD.ownership_revision + 1
       OR NEW.updated_at <= OLD.updated_at THEN
        RAISE EXCEPTION
            'calendar plan ownership source is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_plan_ownership_source_immutable
BEFORE UPDATE ON calendar_plan_ownership
FOR EACH ROW EXECUTE FUNCTION guard_calendar_plan_ownership_source();

CREATE TABLE calendar_ownership_transfer (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    from_owner_user_id UUID NOT NULL,
    to_owner_user_id UUID NOT NULL,
    transfer_status VARCHAR(20) NOT NULL,
    expected_ownership_revision BIGINT NOT NULL,
    transfer_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    offered_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_ownership_transfer_request
        UNIQUE (
            workspace_id, from_owner_user_id,
            operation_request_hash),
    CONSTRAINT uq_calendar_ownership_transfer_identity
        UNIQUE (
            id, plan_id, workspace_id,
            source_created_by_user_id,
            from_owner_user_id, to_owner_user_id),
    CONSTRAINT uq_calendar_ownership_transfer_plan_identity
        UNIQUE (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_ownership_transfer_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan_ownership (
            plan_id, workspace_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_ownership_transfer_from_owner
        FOREIGN KEY (from_owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT fk_calendar_ownership_transfer_target
        FOREIGN KEY (to_owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_calendar_ownership_transfer_actors
        CHECK (from_owner_user_id <> to_owner_user_id),
    CONSTRAINT chk_calendar_ownership_transfer_status
        CHECK (transfer_status IN (
            'OFFERED', 'ACCEPTED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT chk_calendar_ownership_transfer_revision
        CHECK (
            expected_ownership_revision > 0
            AND transfer_revision > 0),
    CONSTRAINT chk_calendar_ownership_transfer_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_calendar_ownership_transfer_time
        CHECK (expires_at > offered_at),
    CONSTRAINT chk_calendar_ownership_transfer_lifecycle
        CHECK (
            (transfer_status = 'OFFERED'
                AND accepted_at IS NULL
                AND canceled_at IS NULL)
            OR (transfer_status = 'ACCEPTED'
                AND accepted_at IS NOT NULL
                AND canceled_at IS NULL)
            OR (transfer_status IN ('EXPIRED', 'CANCELED')
                AND accepted_at IS NULL
                AND canceled_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_calendar_ownership_transfer_offered
    ON calendar_ownership_transfer (
        plan_id, workspace_id, source_created_by_user_id)
    WHERE transfer_status = 'OFFERED';

CREATE FUNCTION guard_calendar_ownership_transfer_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.plan_id IS DISTINCT FROM NEW.plan_id
       OR OLD.from_owner_user_id IS DISTINCT FROM
            NEW.from_owner_user_id
       OR OLD.to_owner_user_id IS DISTINCT FROM
            NEW.to_owner_user_id
       OR OLD.expected_ownership_revision IS DISTINCT FROM
            NEW.expected_ownership_revision
       OR OLD.operation_request_hash IS DISTINCT FROM
            NEW.operation_request_hash
       OR OLD.operation_payload_hash IS DISTINCT FROM
            NEW.operation_payload_hash
       OR OLD.offered_at IS DISTINCT FROM NEW.offered_at
       OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.source_created_by_user_id IS DISTINCT FROM
            NEW.source_created_by_user_id
       OR OLD.transfer_status <> 'OFFERED'
       OR NEW.transfer_status NOT IN (
            'ACCEPTED', 'CANCELED', 'EXPIRED')
       OR (
            NEW.transfer_status = 'ACCEPTED'
            AND CURRENT_TIMESTAMP >= OLD.expires_at)
       OR NEW.transfer_revision <> OLD.transfer_revision + 1
       OR NEW.updated_at <= OLD.updated_at THEN
        RAISE EXCEPTION
            'invalid calendar ownership transfer transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_ownership_transfer_transition
BEFORE UPDATE ON calendar_ownership_transfer
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_ownership_transfer_transition();

CREATE FUNCTION assert_calendar_ownership_transfer_atomicity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id
       AND NOT EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id = NEW.plan_id
              AND transfer.workspace_id = NEW.workspace_id
              AND transfer.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND transfer.from_owner_user_id = OLD.owner_user_id
              AND transfer.to_owner_user_id = NEW.owner_user_id
              AND transfer.expected_ownership_revision =
                    OLD.ownership_revision
              AND transfer.transfer_status = 'ACCEPTED') THEN
        RAISE EXCEPTION
            'ownership change requires accepted transfer in same transaction';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER
    trg_calendar_ownership_requires_accepted_transfer
AFTER UPDATE ON calendar_plan_ownership
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    assert_calendar_ownership_transfer_atomicity();

CREATE FUNCTION assert_calendar_transfer_acceptance_atomicity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.transfer_status = 'ACCEPTED'
       AND NOT EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = NEW.plan_id
              AND ownership.workspace_id = NEW.workspace_id
              AND ownership.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND ownership.owner_user_id = NEW.to_owner_user_id
              AND ownership.ownership_revision =
                    NEW.expected_ownership_revision + 1) THEN
        RAISE EXCEPTION
            'accepted transfer requires ownership change in same transaction';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER
    trg_calendar_transfer_requires_ownership_change
AFTER UPDATE ON calendar_ownership_transfer
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION
    assert_calendar_transfer_acceptance_atomicity();

CREATE TABLE calendar_active_owner_membership_guard (
    plan_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_active_owner_guard_ownership
        FOREIGN KEY (
            plan_id, workspace_id,
            source_created_by_user_id, owner_user_id)
        REFERENCES calendar_plan_ownership (
            plan_id, workspace_id,
            source_created_by_user_id, owner_user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_calendar_active_owner_guard_member
        FOREIGN KEY (workspace_id, owner_user_id)
        REFERENCES workspace_member (workspace_id, user_id)
);

INSERT INTO calendar_active_owner_membership_guard (
    plan_id, workspace_id, owner_user_id,
    source_created_by_user_id)
SELECT ownership.plan_id, ownership.workspace_id,
       ownership.owner_user_id,
       ownership.source_created_by_user_id
FROM calendar_plan_ownership ownership
JOIN calendar_plan plan
  ON plan.id = ownership.plan_id
 AND plan.workspace_id = ownership.workspace_id
 AND plan.created_by_user_id =
        ownership.source_created_by_user_id
WHERE plan.status = 'ACTIVE';

CREATE TABLE calendar_pending_transfer_membership_guard (
    transfer_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    CONSTRAINT fk_calendar_pending_transfer_guard_transfer
        FOREIGN KEY (transfer_id)
        REFERENCES calendar_ownership_transfer (id),
    CONSTRAINT fk_calendar_pending_transfer_guard_member
        FOREIGN KEY (workspace_id, target_user_id)
        REFERENCES workspace_member (workspace_id, user_id)
);

CREATE FUNCTION guard_calendar_active_owner_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() = 1
           AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            JOIN calendar_plan plan
              ON plan.id = ownership.plan_id
             AND plan.workspace_id = ownership.workspace_id
             AND plan.created_by_user_id =
                    ownership.source_created_by_user_id
            WHERE ownership.plan_id = OLD.plan_id
              AND ownership.workspace_id = OLD.workspace_id
              AND ownership.owner_user_id = OLD.owner_user_id
              AND plan.status = 'ACTIVE') THEN
            RAISE EXCEPTION
                'active calendar owner must transfer or archive before leaving';
        END IF;
        RETURN OLD;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_plan_ownership ownership
        JOIN calendar_plan plan
          ON plan.id = ownership.plan_id
         AND plan.workspace_id = ownership.workspace_id
         AND plan.created_by_user_id =
                ownership.source_created_by_user_id
        WHERE ownership.plan_id = NEW.plan_id
          AND ownership.workspace_id = NEW.workspace_id
          AND ownership.owner_user_id = NEW.owner_user_id
          AND ownership.source_created_by_user_id =
                NEW.source_created_by_user_id
          AND plan.status = 'ACTIVE') THEN
        RAISE EXCEPTION
            'calendar owner membership guard must match active ownership';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_active_owner_membership_guard
BEFORE INSERT OR UPDATE OR DELETE
ON calendar_active_owner_membership_guard
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_active_owner_membership();

CREATE FUNCTION guard_calendar_pending_transfer_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() = 1
           AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.id = OLD.transfer_id
              AND transfer.workspace_id = OLD.workspace_id
              AND transfer.to_owner_user_id = OLD.target_user_id
              AND transfer.transfer_status = 'OFFERED') THEN
            RAISE EXCEPTION
                'pending ownership target must resolve offer before leaving';
        END IF;
        RETURN OLD;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM calendar_ownership_transfer transfer
        WHERE transfer.id = NEW.transfer_id
          AND transfer.workspace_id = NEW.workspace_id
          AND transfer.to_owner_user_id = NEW.target_user_id
          AND transfer.transfer_status = 'OFFERED') THEN
        RAISE EXCEPTION
            'pending membership guard must match offered transfer';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_pending_transfer_membership_guard
BEFORE INSERT OR UPDATE OR DELETE
ON calendar_pending_transfer_membership_guard
FOR EACH ROW EXECUTE FUNCTION
    guard_calendar_pending_transfer_membership();

CREATE FUNCTION refresh_calendar_active_owner_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE calendar_active_owner_membership_guard
    SET owner_user_id = NEW.owner_user_id
    WHERE plan_id = NEW.plan_id
      AND workspace_id = NEW.workspace_id
      AND source_created_by_user_id =
            NEW.source_created_by_user_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_refresh_active_owner_membership
AFTER UPDATE OF owner_user_id ON calendar_plan_ownership
FOR EACH ROW EXECUTE FUNCTION
    refresh_calendar_active_owner_membership();

CREATE FUNCTION refresh_calendar_plan_membership_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        INSERT INTO calendar_active_owner_membership_guard (
            plan_id, workspace_id, owner_user_id,
            source_created_by_user_id)
        SELECT ownership.plan_id, ownership.workspace_id,
               ownership.owner_user_id,
               ownership.source_created_by_user_id
        FROM calendar_plan_ownership ownership
        WHERE ownership.plan_id = NEW.id
          AND ownership.workspace_id = NEW.workspace_id
          AND ownership.source_created_by_user_id =
                NEW.created_by_user_id
        ON CONFLICT (plan_id) DO NOTHING;
    ELSE
        DELETE FROM calendar_active_owner_membership_guard
        WHERE plan_id = NEW.id
          AND workspace_id = NEW.workspace_id
          AND source_created_by_user_id =
                NEW.created_by_user_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_refresh_plan_membership_guard
AFTER UPDATE OF status ON calendar_plan
FOR EACH ROW EXECUTE FUNCTION
    refresh_calendar_plan_membership_guard();

CREATE FUNCTION create_calendar_pending_transfer_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO calendar_pending_transfer_membership_guard (
        transfer_id, workspace_id, target_user_id)
    VALUES (NEW.id, NEW.workspace_id, NEW.to_owner_user_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_create_pending_transfer_membership
AFTER INSERT ON calendar_ownership_transfer
FOR EACH ROW EXECUTE FUNCTION
    create_calendar_pending_transfer_membership();

CREATE FUNCTION close_calendar_pending_transfer_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF OLD.transfer_status = 'OFFERED'
       AND NEW.transfer_status <> 'OFFERED' THEN
        DELETE FROM calendar_pending_transfer_membership_guard
        WHERE transfer_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_close_pending_transfer_membership
AFTER UPDATE OF transfer_status ON calendar_ownership_transfer
FOR EACH ROW EXECUTE FUNCTION
    close_calendar_pending_transfer_membership();

CREATE TABLE calendar_organizer_assignment (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    manager_user_id UUID NOT NULL,
    assignment_role VARCHAR(30) NOT NULL,
    roster_permission BOOLEAN NOT NULL,
    notification_recipient BOOLEAN NOT NULL,
    assignment_status VARCHAR(20) NOT NULL,
    assignment_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_organizer_assignment_identity
        UNIQUE (
            id, plan_id, workspace_id,
            source_created_by_user_id, manager_user_id),
    CONSTRAINT uq_calendar_organizer_assignment_request
        UNIQUE (
            workspace_id, source_created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_organizer_assignment_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_organizer_assignment_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_organizer_assignment_manager
        FOREIGN KEY (manager_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_calendar_organizer_assignment_target
        CHECK (
            (target_scope = 'PLAN' AND activity_id IS NULL)
            OR (target_scope = 'ACTIVITY'
                AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_organizer_assignment_role
        CHECK (assignment_role = 'PARTICIPANT_MANAGER'),
    CONSTRAINT chk_calendar_organizer_assignment_status
        CHECK (assignment_status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_calendar_organizer_assignment_revision
        CHECK (assignment_revision > 0),
    CONSTRAINT chk_calendar_organizer_assignment_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_organizer_assignment_active
    ON calendar_organizer_assignment (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, source_created_by_user_id,
        manager_user_id)
    WHERE assignment_status = 'ACTIVE';

CREATE TABLE calendar_registration_policy (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    registration_scope VARCHAR(30) NOT NULL,
    opens_at TIMESTAMPTZ,
    closes_at TIMESTAMPTZ,
    zone_id VARCHAR(80) NOT NULL,
    capacity INTEGER,
    late_join_policy VARCHAR(30) NOT NULL,
    waitlist_enabled BOOLEAN NOT NULL,
    promotion_mode VARCHAR(20) NOT NULL,
    limit_mode VARCHAR(30) NOT NULL,
    offer_ttl_seconds BIGINT NOT NULL,
    late_notification_policy VARCHAR(20) NOT NULL,
    participant_roster_visible BOOLEAN NOT NULL,
    capacity_state VARCHAR(30) NOT NULL,
    policy_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_registration_policy_identity
        UNIQUE (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_registration_policy_request
        UNIQUE (
            workspace_id, source_created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_registration_policy_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_registration_policy_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_registration_policy_target
        CHECK (
            (target_scope = 'PLAN' AND activity_id IS NULL)
            OR (target_scope = 'ACTIVITY'
                AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_registration_scope
        CHECK (registration_scope = 'ONE_OFF'),
    CONSTRAINT chk_calendar_registration_window
        CHECK (
            opens_at IS NULL
            OR closes_at IS NULL
            OR closes_at > opens_at),
    CONSTRAINT chk_calendar_registration_capacity
        CHECK (capacity IS NULL OR capacity > 0),
    CONSTRAINT chk_calendar_registration_late_join
        CHECK (late_join_policy IN (
            'CLOSED', 'WAITLIST_ONLY',
            'REQUIRE_APPROVAL', 'ALLOW_IF_CAPACITY')),
    CONSTRAINT chk_calendar_registration_promotion
        CHECK (promotion_mode IN (
            'MANUAL_OFFER', 'AUTO_OFFER')),
    CONSTRAINT chk_calendar_registration_limit
        CHECK (limit_mode IN (
            'HARD_LIMIT', 'MANAGER_OVERRIDE')),
    CONSTRAINT chk_calendar_registration_offer_ttl
        CHECK (
            offer_ttl_seconds BETWEEN 60 AND 2592000),
    CONSTRAINT chk_calendar_registration_late_notification
        CHECK (late_notification_policy IN (
            'OFF', 'IMMEDIATE')),
    CONSTRAINT chk_calendar_registration_capacity_state
        CHECK (capacity_state IN (
            'WITHIN_CAPACITY', 'OVER_CAPACITY')),
    CONSTRAINT chk_calendar_registration_policy_revision
        CHECK (policy_revision > 0),
    CONSTRAINT chk_calendar_registration_policy_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_registration_policy_target
    ON calendar_registration_policy (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, source_created_by_user_id);

CREATE TABLE calendar_capacity_bucket (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    committed_count INTEGER NOT NULL,
    waitlisted_count INTEGER NOT NULL,
    over_capacity_count INTEGER NOT NULL,
    next_queue_sequence BIGINT NOT NULL,
    bucket_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_capacity_bucket_policy
        UNIQUE (
            policy_id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_capacity_bucket_target
        UNIQUE (
            plan_id, activity_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_capacity_bucket_policy
        FOREIGN KEY (
            policy_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_registration_policy (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT chk_calendar_capacity_bucket_counts
        CHECK (
            committed_count >= 0
            AND waitlisted_count >= 0
            AND over_capacity_count >= 0),
    CONSTRAINT chk_calendar_capacity_bucket_sequence
        CHECK (
            next_queue_sequence > 0
            AND bucket_revision > 0)
);

CREATE TABLE calendar_registration (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    participation_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    target_scope VARCHAR(20) NOT NULL,
    registration_state VARCHAR(30) NOT NULL,
    status_origin VARCHAR(20) NOT NULL,
    applied_policy_revision BIGINT NOT NULL,
    registration_revision BIGINT NOT NULL,
    withdrawal_reason VARCHAR(500),
    withdrawal_reason_shared BOOLEAN NOT NULL,
    removed_by_user_id UUID,
    removal_reason VARCHAR(500),
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    removed_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_registration_identity
        UNIQUE (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_registration_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_registration_policy
        FOREIGN KEY (
            policy_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_registration_policy (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_registration_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_registration_activity
        FOREIGN KEY (
            activity_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_activity (
            id, plan_id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_registration_removed_by
        FOREIGN KEY (removed_by_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_calendar_registration_target
        CHECK (
            (target_scope = 'PLAN' AND activity_id IS NULL)
            OR (target_scope = 'ACTIVITY'
                AND activity_id IS NOT NULL)),
    CONSTRAINT chk_calendar_registration_state
        CHECK (registration_state IN (
            'COMMITTED', 'WAITLISTED',
            'APPROVAL_REQUIRED', 'WITHDRAWN_BY_USER',
            'REMOVED_BY_ORGANIZER', 'DECLINED')),
    CONSTRAINT chk_calendar_registration_origin
        CHECK (status_origin IN ('USER', 'MANAGER', 'SYSTEM')),
    CONSTRAINT chk_calendar_registration_revision
        CHECK (
            applied_policy_revision > 0
            AND registration_revision > 0),
    CONSTRAINT chk_calendar_registration_reason
        CHECK (
            (withdrawal_reason IS NULL
                OR length(btrim(withdrawal_reason))
                    BETWEEN 1 AND 500)
            AND (removal_reason IS NULL
                OR length(btrim(removal_reason))
                    BETWEEN 1 AND 500)),
    CONSTRAINT chk_calendar_registration_lifecycle
        CHECK (
            (registration_state = 'WITHDRAWN_BY_USER'
                AND withdrawn_at IS NOT NULL
                AND removed_at IS NULL
                AND removed_by_user_id IS NULL
                AND removal_reason IS NULL)
            OR (registration_state = 'REMOVED_BY_ORGANIZER'
                AND withdrawn_at IS NULL
                AND removed_at IS NOT NULL
                AND removed_by_user_id IS NOT NULL
                AND removal_reason IS NOT NULL)
            OR (registration_state NOT IN (
                    'WITHDRAWN_BY_USER',
                    'REMOVED_BY_ORGANIZER')
                AND withdrawn_at IS NULL
                AND removed_at IS NULL
                AND removed_by_user_id IS NULL
                AND removal_reason IS NULL)),
    CONSTRAINT chk_calendar_registration_withdrawal_privacy
        CHECK (
            withdrawal_reason IS NOT NULL
            OR NOT withdrawal_reason_shared),
    CONSTRAINT chk_calendar_registration_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_registration_actor_target
    ON calendar_registration (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id,
        source_created_by_user_id);

CREATE INDEX idx_calendar_registration_roster
    ON calendar_registration (
        workspace_id, source_created_by_user_id,
        plan_id, activity_id, registration_state);

CREATE TABLE calendar_registration_history (
    id UUID PRIMARY KEY,
    registration_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    event_type VARCHAR(40) NOT NULL,
    previous_state VARCHAR(30),
    current_state VARCHAR(30) NOT NULL,
    status_origin VARCHAR(20) NOT NULL,
    registration_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    reason_present BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_registration_history_revision
        UNIQUE (registration_id, registration_revision),
    CONSTRAINT uq_calendar_registration_history_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_registration_history_registration
        FOREIGN KEY (
            registration_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_registration (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_registration_history_event
        CHECK (event_type IN (
            'JOINED', 'WAITLISTED', 'APPROVAL_REQUESTED',
            'OFFER_ACCEPTED', 'WITHDRAWN_BY_USER',
            'REMOVED_BY_ORGANIZER', 'OVERRIDE_COMMITTED',
            'CAPACITY_STATE_CHANGED')),
    CONSTRAINT chk_calendar_registration_history_states
        CHECK (
            current_state IN (
                'COMMITTED', 'WAITLISTED',
                'APPROVAL_REQUIRED', 'WITHDRAWN_BY_USER',
                'REMOVED_BY_ORGANIZER', 'DECLINED')
            AND (
                previous_state IS NULL
                OR previous_state IN (
                    'COMMITTED', 'WAITLISTED',
                    'APPROVAL_REQUIRED', 'WITHDRAWN_BY_USER',
                    'REMOVED_BY_ORGANIZER', 'DECLINED'))),
    CONSTRAINT chk_calendar_registration_history_origin
        CHECK (status_origin IN ('USER', 'MANAGER', 'SYSTEM')),
    CONSTRAINT chk_calendar_registration_history_revision
        CHECK (registration_revision > 0),
    CONSTRAINT chk_calendar_registration_history_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE calendar_waitlist_entry (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    registration_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    queue_sequence BIGINT NOT NULL,
    entry_state VARCHAR(20) NOT NULL,
    entry_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    exited_at TIMESTAMPTZ,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_waitlist_entry_identity
        UNIQUE (
            id, registration_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT uq_calendar_waitlist_entry_registration
        UNIQUE (
            registration_id, workspace_id,
            created_by_user_id),
    CONSTRAINT uq_calendar_waitlist_entry_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_waitlist_entry_policy
        FOREIGN KEY (
            policy_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_registration_policy (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_waitlist_entry_registration
        FOREIGN KEY (
            registration_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_registration (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_waitlist_entry_sequence
        CHECK (queue_sequence > 0 AND entry_revision > 0),
    CONSTRAINT chk_calendar_waitlist_entry_state
        CHECK (entry_state IN (
            'WAITING', 'OFFERED', 'PROMOTED',
            'WITHDRAWN', 'EXPIRED', 'REMOVED')),
    CONSTRAINT chk_calendar_waitlist_entry_lifecycle
        CHECK (
            (entry_state IN ('WAITING', 'OFFERED')
                AND exited_at IS NULL)
            OR (entry_state NOT IN ('WAITING', 'OFFERED')
                AND exited_at IS NOT NULL)),
    CONSTRAINT chk_calendar_waitlist_entry_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_waitlist_target_sequence
    ON calendar_waitlist_entry (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, source_created_by_user_id,
        queue_sequence);

CREATE INDEX idx_calendar_waitlist_fifo
    ON calendar_waitlist_entry (
        workspace_id, source_created_by_user_id,
        plan_id, activity_id, entry_state, queue_sequence);

CREATE TABLE calendar_waitlist_offer (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL,
    registration_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    offer_status VARCHAR(20) NOT NULL,
    applied_policy_revision BIGINT NOT NULL,
    offer_revision BIGINT NOT NULL,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    offered_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_waitlist_offer_identity
        UNIQUE (
            id, entry_id, registration_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_waitlist_offer_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_waitlist_offer_entry
        FOREIGN KEY (
            entry_id, registration_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id)
        REFERENCES calendar_waitlist_entry (
            id, registration_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_waitlist_offer_policy
        FOREIGN KEY (
            policy_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_registration_policy (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT chk_calendar_waitlist_offer_status
        CHECK (offer_status IN (
            'OFFERED', 'ACCEPTED', 'EXPIRED',
            'DECLINED', 'CANCELED')),
    CONSTRAINT chk_calendar_waitlist_offer_revision
        CHECK (
            applied_policy_revision > 0
            AND offer_revision > 0),
    CONSTRAINT chk_calendar_waitlist_offer_time
        CHECK (expires_at > offered_at),
    CONSTRAINT chk_calendar_waitlist_offer_lifecycle
        CHECK (
            (offer_status = 'OFFERED'
                AND responded_at IS NULL)
            OR (offer_status <> 'OFFERED'
                AND responded_at IS NOT NULL)),
    CONSTRAINT chk_calendar_waitlist_offer_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_waitlist_offer_active_target
    ON calendar_waitlist_offer (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, source_created_by_user_id)
    WHERE offer_status = 'OFFERED';

CREATE TABLE calendar_participant_minimum_access (
    id UUID PRIMARY KEY,
    registration_id UUID NOT NULL,
    participation_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    access_status VARCHAR(20) NOT NULL,
    access_revision BIGINT NOT NULL,
    source_access_revoked_at TIMESTAMPTZ NOT NULL,
    terminated_at TIMESTAMPTZ,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_participant_minimum_identity
        UNIQUE (
            id, registration_id, plan_id,
            workspace_id, created_by_user_id,
            source_created_by_user_id),
    CONSTRAINT uq_calendar_participant_minimum_request
        UNIQUE (
            workspace_id, created_by_user_id,
            operation_request_hash),
    CONSTRAINT fk_calendar_participant_minimum_registration
        FOREIGN KEY (
            registration_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_registration (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT fk_calendar_participant_minimum_participation
        FOREIGN KEY (
            participation_id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id)
        REFERENCES calendar_participation (
            id, plan_id, workspace_id,
            created_by_user_id, source_created_by_user_id),
    CONSTRAINT chk_calendar_participant_minimum_status
        CHECK (access_status IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT chk_calendar_participant_minimum_revision
        CHECK (access_revision > 0),
    CONSTRAINT chk_calendar_participant_minimum_lifecycle
        CHECK (
            (access_status = 'ACTIVE' AND terminated_at IS NULL)
            OR (access_status = 'TERMINATED'
                AND terminated_at IS NOT NULL)),
    CONSTRAINT chk_calendar_participant_minimum_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_calendar_participant_minimum_active
    ON calendar_participant_minimum_access (
        plan_id,
        COALESCE(
            activity_id,
            '00000000-0000-0000-0000-000000000000'::uuid),
        workspace_id, created_by_user_id,
        source_created_by_user_id)
    WHERE access_status = 'ACTIVE';

CREATE TABLE calendar_registration_request_receipt (
    id UUID PRIMARY KEY,
    request_kind VARCHAR(40) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    registration_id UUID,
    waitlist_entry_id UUID,
    waitlist_offer_id UUID,
    transfer_id UUID,
    operation_request_hash VARCHAR(64) NOT NULL,
    operation_payload_hash VARCHAR(64) NOT NULL,
    semantic_identity_hash VARCHAR(64) NOT NULL,
    result_state VARCHAR(30) NOT NULL,
    result_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    source_created_by_user_id UUID NOT NULL,
    CONSTRAINT uq_calendar_registration_receipt_request
        UNIQUE (
            workspace_id, created_by_user_id,
            request_kind, operation_request_hash),
    CONSTRAINT uq_calendar_registration_receipt_semantic
        UNIQUE (
            workspace_id, created_by_user_id,
            request_kind, semantic_identity_hash),
    CONSTRAINT fk_calendar_registration_receipt_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT chk_calendar_registration_receipt_kind
        CHECK (request_kind IN (
            'POLICY_CHANGE', 'MANAGER_ASSIGNMENT',
            'REGISTRATION_CHANGE', 'WAITLIST_OFFER',
            'WAITLIST_RESPONSE', 'ORGANIZER_REMOVAL',
            'CAPACITY_OVERRIDE', 'MINIMUM_ACCESS',
            'OWNERSHIP_TRANSFER')),
    CONSTRAINT chk_calendar_registration_receipt_revision
        CHECK (result_revision > 0),
    CONSTRAINT chk_calendar_registration_receipt_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$'
            AND semantic_identity_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE calendar_registration_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    plan_id UUID NOT NULL,
    activity_id UUID,
    registration_id UUID,
    waitlist_offer_id UUID,
    transfer_id UUID,
    recipient_user_id UUID NOT NULL,
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
    CONSTRAINT uq_calendar_registration_outbox_request
        UNIQUE (
            workspace_id, recipient_user_id,
            event_type, operation_request_hash),
    CONSTRAINT uq_calendar_registration_outbox_semantic
        UNIQUE (
            workspace_id, recipient_user_id,
            event_type, semantic_identity_hash),
    CONSTRAINT fk_calendar_registration_outbox_plan
        FOREIGN KEY (
            plan_id, workspace_id, source_created_by_user_id)
        REFERENCES calendar_plan (
            id, workspace_id, created_by_user_id),
    CONSTRAINT fk_calendar_registration_outbox_transfer
        FOREIGN KEY (
            transfer_id, plan_id, workspace_id,
            source_created_by_user_id)
        REFERENCES calendar_ownership_transfer (
            id, plan_id, workspace_id,
            source_created_by_user_id),
    CONSTRAINT fk_calendar_registration_outbox_recipient
        FOREIGN KEY (recipient_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_calendar_registration_outbox_event
        CHECK (event_type IN (
            'REGISTRATION_RESULT', 'LATE_REGISTRATION',
            'WAITLIST_OFFERED', 'WAITLIST_OFFER_EXPIRED',
            'WITHDRAWN_BY_USER', 'REMOVED_BY_ORGANIZER',
            'CAPACITY_OVER_CAPACITY',
            'PARTICIPANT_MINIMUM_ACCESS',
            'OWNERSHIP_TRANSFER_OFFERED',
            'OWNERSHIP_TRANSFER_ACCEPTED',
            'OWNERSHIP_TRANSFER_CANCELED',
            'OWNERSHIP_TRANSFER_EXPIRED')),
    CONSTRAINT chk_calendar_registration_outbox_payload
        CHECK (
            length(btrim(payload_text)) BETWEEN 1 AND 1000),
    CONSTRAINT chk_calendar_registration_outbox_delivery
        CHECK (
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
    CONSTRAINT chk_calendar_registration_outbox_attempts
        CHECK (delivery_attempt_count >= 0),
    CONSTRAINT chk_calendar_registration_outbox_failure
        CHECK (
            last_delivery_failure IS NULL
            OR length(btrim(last_delivery_failure))
                BETWEEN 1 AND 500),
    CONSTRAINT chk_calendar_registration_outbox_hashes
        CHECK (
            operation_request_hash ~ '^[0-9a-f]{64}$'
            AND operation_payload_hash ~ '^[0-9a-f]{64}$'
            AND semantic_identity_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_calendar_registration_outbox_delivery
    ON calendar_registration_outbox (
        workspace_id, recipient_user_id,
        delivery_status, next_delivery_attempt_at, created_at);

CREATE FUNCTION reject_calendar_registration_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar registration history is append-only';
END;
$$;

CREATE TRIGGER trg_calendar_registration_history_append_only
BEFORE UPDATE OR DELETE ON calendar_registration_history
FOR EACH ROW EXECUTE FUNCTION reject_calendar_registration_append_only();

CREATE FUNCTION reject_calendar_registration_outbox_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'calendar registration outbox deletion is forbidden';
END;
$$;

CREATE TRIGGER trg_calendar_registration_outbox_no_delete
BEFORE DELETE ON calendar_registration_outbox
FOR EACH ROW EXECUTE FUNCTION reject_calendar_registration_outbox_delete();

ALTER TABLE calendar_plan_ownership
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_plan_ownership
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_plan_ownership_select
    ON calendar_plan_ownership FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            app_actor_matches(source_created_by_user_id)
            OR app_actor_matches(owner_user_id)));
CREATE POLICY rls_calendar_plan_ownership_insert
    ON calendar_plan_ownership FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(source_created_by_user_id)
        AND owner_user_id = source_created_by_user_id);
CREATE POLICY rls_calendar_plan_ownership_transfer_accept
    ON calendar_plan_ownership FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id =
                    calendar_plan_ownership.plan_id
              AND transfer.workspace_id =
                    calendar_plan_ownership.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_plan_ownership
                        .source_created_by_user_id
              AND transfer.from_owner_user_id =
                    calendar_plan_ownership.owner_user_id
              AND transfer.expected_ownership_revision =
                    calendar_plan_ownership
                        .ownership_revision
              AND transfer.transfer_status = 'OFFERED'
              AND transfer.expires_at > CURRENT_TIMESTAMP
              AND app_actor_matches(
                    transfer.to_owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(owner_user_id));
CREATE POLICY rls_calendar_plan_ownership_transfer_target_select
    ON calendar_plan_ownership FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id =
                    calendar_plan_ownership.plan_id
              AND transfer.workspace_id =
                    calendar_plan_ownership.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_plan_ownership
                        .source_created_by_user_id
              AND transfer.from_owner_user_id =
                    calendar_plan_ownership.owner_user_id
              AND transfer.expected_ownership_revision =
                    calendar_plan_ownership
                        .ownership_revision
              AND transfer.transfer_status = 'OFFERED'
              AND app_actor_matches(
                    transfer.to_owner_user_id)));

ALTER TABLE calendar_ownership_transfer
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_ownership_transfer
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_ownership_transfer_select
    ON calendar_ownership_transfer FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            app_actor_matches(from_owner_user_id)
            OR app_actor_matches(to_owner_user_id)));
CREATE POLICY rls_calendar_ownership_transfer_insert
    ON calendar_ownership_transfer FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(from_owner_user_id));
CREATE POLICY rls_calendar_ownership_transfer_accept
    ON calendar_ownership_transfer FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND transfer_status = 'OFFERED'
        AND expires_at > CURRENT_TIMESTAMP
        AND app_actor_matches(to_owner_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND transfer_status = 'ACCEPTED'
        AND app_actor_matches(to_owner_user_id));
CREATE POLICY rls_calendar_ownership_transfer_expire
    ON calendar_ownership_transfer FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND transfer_status = 'OFFERED'
        AND app_actor_matches(to_owner_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND transfer_status = 'EXPIRED'
        AND app_actor_matches(to_owner_user_id));
CREATE POLICY rls_calendar_ownership_transfer_cancel
    ON calendar_ownership_transfer FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND transfer_status = 'OFFERED'
        AND app_actor_matches(from_owner_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND transfer_status IN ('CANCELED', 'EXPIRED')
        AND app_actor_matches(from_owner_user_id));

ALTER TABLE calendar_active_owner_membership_guard
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_active_owner_membership_guard
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_active_owner_guard_select
    ON calendar_active_owner_membership_guard FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(owner_user_id));
CREATE POLICY rls_calendar_active_owner_guard_transfer_target_select
    ON calendar_active_owner_membership_guard FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id =
                    calendar_active_owner_membership_guard.plan_id
              AND transfer.workspace_id =
                    calendar_active_owner_membership_guard.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_active_owner_membership_guard
                        .source_created_by_user_id
              AND transfer.from_owner_user_id =
                    calendar_active_owner_membership_guard.owner_user_id
              AND transfer.transfer_status = 'OFFERED'
              AND app_actor_matches(transfer.to_owner_user_id)));
CREATE POLICY rls_calendar_active_owner_guard_insert
    ON calendar_active_owner_membership_guard FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(owner_user_id));
CREATE POLICY rls_calendar_active_owner_guard_update
    ON calendar_active_owner_membership_guard FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id =
                    calendar_active_owner_membership_guard.plan_id
              AND transfer.workspace_id =
                    calendar_active_owner_membership_guard.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_active_owner_membership_guard
                        .source_created_by_user_id
              AND transfer.from_owner_user_id =
                    calendar_active_owner_membership_guard.owner_user_id
              AND transfer.transfer_status = 'OFFERED'
              AND app_actor_matches(transfer.to_owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(owner_user_id));
CREATE POLICY rls_calendar_active_owner_guard_delete
    ON calendar_active_owner_membership_guard FOR DELETE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(owner_user_id));

ALTER TABLE calendar_pending_transfer_membership_guard
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_pending_transfer_membership_guard
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_pending_transfer_guard_select
    ON calendar_pending_transfer_membership_guard FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(target_user_id));
CREATE POLICY rls_calendar_pending_transfer_guard_insert
    ON calendar_pending_transfer_membership_guard FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.id =
                    calendar_pending_transfer_membership_guard.transfer_id
              AND transfer.workspace_id =
                    calendar_pending_transfer_membership_guard.workspace_id
              AND transfer.to_owner_user_id =
                    calendar_pending_transfer_membership_guard.target_user_id
              AND transfer.transfer_status = 'OFFERED'
              AND app_actor_matches(transfer.from_owner_user_id)));
CREATE POLICY rls_calendar_pending_transfer_guard_delete
    ON calendar_pending_transfer_membership_guard FOR DELETE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.id =
                    calendar_pending_transfer_membership_guard.transfer_id
              AND transfer.workspace_id =
                    calendar_pending_transfer_membership_guard.workspace_id
              AND (
                    app_actor_matches(transfer.from_owner_user_id)
                    OR app_actor_matches(transfer.to_owner_user_id))));

ALTER TABLE calendar_organizer_assignment
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_organizer_assignment
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_organizer_assignment_select
    ON calendar_organizer_assignment FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            app_actor_matches(manager_user_id)
            OR EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_organizer_assignment.plan_id
                  AND ownership.workspace_id =
                        calendar_organizer_assignment.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_organizer_assignment
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))));
CREATE POLICY rls_calendar_organizer_assignment_insert
    ON calendar_organizer_assignment FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_organizer_assignment.plan_id
              AND ownership.workspace_id =
                    calendar_organizer_assignment.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_organizer_assignment
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));
CREATE POLICY rls_calendar_organizer_assignment_update
    ON calendar_organizer_assignment FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_organizer_assignment.plan_id
              AND ownership.workspace_id =
                    calendar_organizer_assignment.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_organizer_assignment
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_registration_policy
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_registration_policy
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_registration_policy_manager_select
    ON calendar_registration_policy FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_registration_policy.plan_id
                  AND ownership.workspace_id =
                        calendar_registration_policy.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_registration_policy
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_registration_policy.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_registration_policy.activity_id
                  AND assignment.workspace_id =
                        calendar_registration_policy.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_registration_policy
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))));
CREATE POLICY rls_calendar_registration_policy_owner_insert
    ON calendar_registration_policy FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_registration_policy.plan_id
              AND ownership.workspace_id =
                    calendar_registration_policy.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_registration_policy
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));
CREATE POLICY rls_calendar_registration_policy_owner_update
    ON calendar_registration_policy FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_registration_policy.plan_id
              AND ownership.workspace_id =
                    calendar_registration_policy.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_registration_policy
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));
CREATE POLICY rls_calendar_registration_policy_grantee_select
    ON calendar_registration_policy FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.plan_id =
                    calendar_registration_policy.plan_id
              AND share_row.workspace_id =
                    calendar_registration_policy.workspace_id
              AND share_row.created_by_user_id =
                    calendar_registration_policy
                        .source_created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'));

ALTER TABLE calendar_capacity_bucket
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_capacity_bucket
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_capacity_bucket_manager
    ON calendar_capacity_bucket FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_capacity_bucket.plan_id
                  AND ownership.workspace_id =
                        calendar_capacity_bucket.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_capacity_bucket
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_capacity_bucket.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_capacity_bucket.activity_id
                  AND assignment.workspace_id =
                        calendar_capacity_bucket.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_capacity_bucket
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))))
    WITH CHECK (app_workspace_matches(workspace_id));
CREATE POLICY rls_calendar_capacity_bucket_grantee_select
    ON calendar_capacity_bucket FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.plan_id =
                    calendar_capacity_bucket.plan_id
              AND share_row.workspace_id =
                    calendar_capacity_bucket.workspace_id
              AND share_row.created_by_user_id =
                    calendar_capacity_bucket
                        .source_created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'));
CREATE POLICY rls_calendar_capacity_bucket_grantee_update
    ON calendar_capacity_bucket FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.plan_id =
                    calendar_capacity_bucket.plan_id
              AND share_row.workspace_id =
                    calendar_capacity_bucket.workspace_id
              AND share_row.created_by_user_id =
                    calendar_capacity_bucket
                        .source_created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'))
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_registration
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_registration
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_registration_actor_select
    ON calendar_registration FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_registration_manager_select
    ON calendar_registration FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_registration.plan_id
                  AND ownership.workspace_id =
                        calendar_registration.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_registration
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_registration.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_registration.activity_id
                  AND assignment.workspace_id =
                        calendar_registration.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_registration
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND assignment.roster_permission
                  AND app_actor_matches(
                        assignment.manager_user_id))));
CREATE POLICY rls_calendar_registration_actor_insert
    ON calendar_registration FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_registration_actor_update
    ON calendar_registration FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_registration_manager_update
    ON calendar_registration FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_registration.plan_id
                  AND ownership.workspace_id =
                        calendar_registration.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_registration
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_registration.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_registration.activity_id
                  AND assignment.workspace_id =
                        calendar_registration.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_registration
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))))
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_registration_history
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_registration_history
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_registration_history_select
    ON calendar_registration_history FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            app_actor_matches(created_by_user_id)
            OR EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_registration_history.plan_id
                  AND ownership.workspace_id =
                        calendar_registration_history.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_registration_history
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_registration_history.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_registration_history.activity_id
                  AND assignment.workspace_id =
                        calendar_registration_history.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_registration_history
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))));
CREATE POLICY rls_calendar_registration_history_insert
    ON calendar_registration_history FOR INSERT
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_waitlist_entry
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_waitlist_entry
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_waitlist_entry_actor_select
    ON calendar_waitlist_entry FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_waitlist_entry_manager
    ON calendar_waitlist_entry FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_waitlist_entry.plan_id
                  AND ownership.workspace_id =
                        calendar_waitlist_entry.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_waitlist_entry
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_waitlist_entry.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_waitlist_entry.activity_id
                  AND assignment.workspace_id =
                        calendar_waitlist_entry.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_waitlist_entry
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))))
    WITH CHECK (app_workspace_matches(workspace_id));
CREATE POLICY rls_calendar_waitlist_entry_actor_insert
    ON calendar_waitlist_entry FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_waitlist_entry_actor_update
    ON calendar_waitlist_entry FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_waitlist_offer
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_waitlist_offer
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_waitlist_offer_actor_select
    ON calendar_waitlist_offer FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_waitlist_offer_manager
    ON calendar_waitlist_offer FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_waitlist_offer.plan_id
                  AND ownership.workspace_id =
                        calendar_waitlist_offer.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_waitlist_offer
                            .source_created_by_user_id
                  AND app_actor_matches(
                        ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_waitlist_offer.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_waitlist_offer.activity_id
                  AND assignment.workspace_id =
                        calendar_waitlist_offer.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_waitlist_offer
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND app_actor_matches(
                        assignment.manager_user_id))))
    WITH CHECK (app_workspace_matches(workspace_id));
CREATE POLICY rls_calendar_waitlist_offer_actor_update
    ON calendar_waitlist_offer FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE calendar_participant_minimum_access
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_participant_minimum_access
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_participant_minimum_actor_select
    ON calendar_participant_minimum_access FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_participant_minimum_manager
    ON calendar_participant_minimum_access FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_participant_minimum_access.plan_id
              AND ownership.workspace_id =
                    calendar_participant_minimum_access.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_participant_minimum_access
                        .source_created_by_user_id
              AND app_actor_matches(
                    ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_registration_request_receipt
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_registration_request_receipt
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_registration_receipt_actor_select
    ON calendar_registration_request_receipt FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_calendar_registration_receipt_insert
    ON calendar_registration_request_receipt FOR INSERT
    WITH CHECK (app_workspace_matches(workspace_id));

ALTER TABLE calendar_registration_outbox
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_registration_outbox
    FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_calendar_registration_outbox_recipient
    ON calendar_registration_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(recipient_user_id))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(recipient_user_id));
CREATE POLICY rls_calendar_registration_outbox_owner
    ON calendar_registration_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_registration_outbox.plan_id
              AND ownership.workspace_id =
                    calendar_registration_outbox.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_registration_outbox
                        .source_created_by_user_id
              AND app_actor_matches(
                    ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_organizer_assignment_grantee_select
    ON calendar_organizer_assignment FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            WHERE share_row.plan_id =
                    calendar_organizer_assignment.plan_id
              AND share_row.workspace_id =
                    calendar_organizer_assignment.workspace_id
              AND share_row.created_by_user_id =
                    calendar_organizer_assignment
                        .source_created_by_user_id
              AND app_actor_matches(share_row.grantee_user_id)
              AND share_row.status = 'ACTIVE'));

CREATE POLICY rls_calendar_participation_registration_manager_select
    ON calendar_participation FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_participation.plan_id
                  AND ownership.workspace_id =
                        calendar_participation.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_participation
                            .source_created_by_user_id
                  AND app_actor_matches(ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_participation.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_participation.activity_id
                  AND assignment.workspace_id =
                        calendar_participation.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_participation
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND assignment.roster_permission
                  AND app_actor_matches(
                        assignment.manager_user_id))));

CREATE POLICY rls_calendar_participation_registration_manager_update
    ON calendar_participation FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_participation.plan_id
                  AND ownership.workspace_id =
                        calendar_participation.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_participation
                            .source_created_by_user_id
                  AND app_actor_matches(ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_participation.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_participation.activity_id
                  AND assignment.workspace_id =
                        calendar_participation.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_participation
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND assignment.roster_permission
                  AND app_actor_matches(
                        assignment.manager_user_id))))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND participation_state = 'OPTED_OUT');

CREATE POLICY rls_calendar_participation_history_registration_manager_insert
    ON calendar_participation_history FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND current_state = 'OPTED_OUT'
        AND previous_state IS NOT NULL
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_participation_history.plan_id
                  AND ownership.workspace_id =
                        calendar_participation_history.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_participation_history
                            .source_created_by_user_id
                  AND app_actor_matches(ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_participation_history.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_participation_history.activity_id
                  AND assignment.workspace_id =
                        calendar_participation_history.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_participation_history
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND assignment.roster_permission
                  AND app_actor_matches(
                        assignment.manager_user_id))));

CREATE POLICY rls_calendar_participation_history_registration_manager_select
    ON calendar_participation_history FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND (
            EXISTS (
                SELECT 1
                FROM calendar_plan_ownership ownership
                WHERE ownership.plan_id =
                        calendar_participation_history.plan_id
                  AND ownership.workspace_id =
                        calendar_participation_history.workspace_id
                  AND ownership.source_created_by_user_id =
                        calendar_participation_history
                            .source_created_by_user_id
                  AND app_actor_matches(ownership.owner_user_id))
            OR EXISTS (
                SELECT 1
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id =
                        calendar_participation_history.plan_id
                  AND assignment.activity_id IS NOT DISTINCT FROM
                        calendar_participation_history.activity_id
                  AND assignment.workspace_id =
                        calendar_participation_history.workspace_id
                  AND assignment.source_created_by_user_id =
                        calendar_participation_history
                            .source_created_by_user_id
                  AND assignment.assignment_status = 'ACTIVE'
                  AND assignment.roster_permission
                  AND app_actor_matches(
                        assignment.manager_user_id))));

CREATE POLICY rls_calendar_registration_outbox_operation_insert
    ON calendar_registration_outbox FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_registration registration
            WHERE registration.id =
                    calendar_registration_outbox.registration_id
              AND registration.plan_id =
                    calendar_registration_outbox.plan_id
              AND registration.activity_id IS NOT DISTINCT FROM
                    calendar_registration_outbox.activity_id
              AND registration.workspace_id =
                    calendar_registration_outbox.workspace_id
              AND registration.source_created_by_user_id =
                    calendar_registration_outbox
                        .source_created_by_user_id
              AND (
                  app_actor_matches(
                        registration.created_by_user_id)
                  OR EXISTS (
                      SELECT 1
                      FROM calendar_plan_ownership ownership
                      WHERE ownership.plan_id =
                            registration.plan_id
                        AND ownership.workspace_id =
                            registration.workspace_id
                        AND ownership.source_created_by_user_id =
                            registration.source_created_by_user_id
                        AND app_actor_matches(
                            ownership.owner_user_id))
                  OR EXISTS (
                      SELECT 1
                      FROM calendar_organizer_assignment initiator
                      WHERE initiator.plan_id =
                            registration.plan_id
                        AND initiator.activity_id IS NOT DISTINCT FROM
                            registration.activity_id
                        AND initiator.workspace_id =
                            registration.workspace_id
                        AND initiator.source_created_by_user_id =
                            registration.source_created_by_user_id
                        AND initiator.assignment_status = 'ACTIVE'
                        AND initiator.roster_permission
                        AND app_actor_matches(
                            initiator.manager_user_id)))
              AND (
                  recipient_user_id =
                        registration.created_by_user_id
                  OR EXISTS (
                      SELECT 1
                      FROM calendar_organizer_assignment recipient
                      WHERE recipient.plan_id =
                            registration.plan_id
                        AND recipient.activity_id IS NOT DISTINCT FROM
                            registration.activity_id
                        AND recipient.workspace_id =
                            registration.workspace_id
                        AND recipient.source_created_by_user_id =
                            registration.source_created_by_user_id
                        AND recipient.assignment_status = 'ACTIVE'
                        AND recipient.notification_recipient
                        AND recipient.manager_user_id =
                            calendar_registration_outbox
                                .recipient_user_id))));

CREATE POLICY rls_calendar_registration_outbox_transfer_insert
    ON calendar_registration_outbox FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.id =
                    calendar_registration_outbox.transfer_id
              AND transfer.plan_id =
                    calendar_registration_outbox.plan_id
              AND transfer.workspace_id =
                    calendar_registration_outbox.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_registration_outbox
                        .source_created_by_user_id
              AND app_current_actor_id() IN (
                    transfer.from_owner_user_id,
                    transfer.to_owner_user_id)
              AND recipient_user_id IN (
                    transfer.from_owner_user_id,
                    transfer.to_owner_user_id)));

CREATE POLICY rls_calendar_plan_participant_manager_select
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_organizer_assignment assignment
            WHERE assignment.plan_id = calendar_plan.id
              AND assignment.workspace_id =
                    calendar_plan.workspace_id
              AND assignment.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND assignment.assignment_status = 'ACTIVE'
              AND app_actor_matches(
                    assignment.manager_user_id)));

CREATE POLICY rls_calendar_plan_effective_owner_select
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_plan.id
              AND ownership.workspace_id =
                    calendar_plan.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_plan_transfer_target_select
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_ownership_transfer transfer
            WHERE transfer.plan_id = calendar_plan.id
              AND transfer.workspace_id =
                    calendar_plan.workspace_id
              AND transfer.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND transfer.transfer_status = 'OFFERED'
              AND app_actor_matches(
                    transfer.to_owner_user_id)));

CREATE POLICY rls_calendar_activity_participant_manager_select
    ON calendar_activity FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_organizer_assignment assignment
            WHERE assignment.plan_id = calendar_activity.plan_id
              AND assignment.activity_id IS NOT DISTINCT FROM
                    calendar_activity.id
              AND assignment.workspace_id =
                    calendar_activity.workspace_id
              AND assignment.source_created_by_user_id =
                    calendar_activity.created_by_user_id
              AND assignment.assignment_status = 'ACTIVE'
              AND app_actor_matches(
                    assignment.manager_user_id)));

CREATE POLICY rls_calendar_plan_participant_minimum_select
    ON calendar_plan FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_participant_minimum_access minimum_access
            WHERE minimum_access.plan_id = calendar_plan.id
              AND minimum_access.workspace_id =
                    calendar_plan.workspace_id
              AND minimum_access.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND minimum_access.access_status = 'ACTIVE'
              AND app_actor_matches(
                    minimum_access.created_by_user_id)));

CREATE POLICY rls_calendar_activity_participant_minimum_select
    ON calendar_activity FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_participant_minimum_access minimum_access
            WHERE minimum_access.plan_id =
                    calendar_activity.plan_id
              AND minimum_access.activity_id IS NOT DISTINCT FROM
                    calendar_activity.id
              AND minimum_access.workspace_id =
                    calendar_activity.workspace_id
              AND minimum_access.source_created_by_user_id =
                    calendar_activity.created_by_user_id
              AND minimum_access.access_status = 'ACTIVE'
              AND app_actor_matches(
                    minimum_access.created_by_user_id)));

CREATE OR REPLACE FUNCTION
    require_calendar_participation_exit_confirmation()
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
                    NEW.operation_request_hash)
           AND NOT EXISTS (
            SELECT 1
            FROM calendar_registration registration
            JOIN calendar_registration_history history
              ON history.registration_id = registration.id
             AND history.plan_id = registration.plan_id
             AND history.workspace_id = registration.workspace_id
             AND history.created_by_user_id =
                    registration.created_by_user_id
             AND history.source_created_by_user_id =
                    registration.source_created_by_user_id
            WHERE registration.participation_id =
                    NEW.participation_id
              AND registration.plan_id = NEW.plan_id
              AND registration.activity_id IS NOT DISTINCT FROM
                    NEW.activity_id
              AND registration.workspace_id = NEW.workspace_id
              AND registration.created_by_user_id =
                    NEW.created_by_user_id
              AND registration.source_created_by_user_id =
                    NEW.source_created_by_user_id
              AND registration.registration_state IN (
                    'WITHDRAWN_BY_USER',
                    'REMOVED_BY_ORGANIZER')
              AND history.current_state =
                    registration.registration_state
              AND history.registration_revision =
                    registration.registration_revision
              AND history.operation_request_hash =
                    NEW.operation_request_hash
              AND history.operation_payload_hash =
                    NEW.operation_payload_hash) THEN
            RAISE EXCEPTION
                'committed or required participation exit requires exact confirmed suppression';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE POLICY rls_calendar_plan_effective_owner_update
    ON calendar_plan FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_plan.id
              AND ownership.workspace_id = calendar_plan.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_plan.id
              AND ownership.workspace_id = calendar_plan.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_plan.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_activity_effective_owner
    ON calendar_activity FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_activity.plan_id
              AND ownership.workspace_id = calendar_activity.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_activity.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_activity.plan_id
              AND ownership.workspace_id = calendar_activity.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_activity.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_time_node_effective_owner
    ON calendar_time_node FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_time_node.plan_id
              AND ownership.workspace_id = calendar_time_node.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_time_node.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_time_node.plan_id
              AND ownership.workspace_id = calendar_time_node.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_time_node.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_online_link_effective_owner
    ON calendar_online_access_link FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_online_access_link.plan_id
              AND ownership.workspace_id =
                    calendar_online_access_link.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_online_access_link.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_online_access_link.plan_id
              AND ownership.workspace_id =
                    calendar_online_access_link.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_online_access_link.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_projection_signal_effective_owner_insert
    ON calendar_personal_projection_signal FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_personal_projection_signal.plan_id
              AND ownership.workspace_id =
                    calendar_personal_projection_signal.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_personal_projection_signal.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE POLICY rls_calendar_reminder_rule_effective_owner_update
    ON calendar_reminder_rule FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_reminder_rule.plan_id
              AND ownership.workspace_id =
                    calendar_reminder_rule.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_reminder_rule.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_reminder_occurrence_effective_owner_update
    ON calendar_reminder_occurrence FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_reminder_occurrence.plan_id
              AND ownership.workspace_id =
                    calendar_reminder_occurrence.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_reminder_occurrence.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_adoption_effective_owner_update
    ON calendar_adoption FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_adoption.plan_id
              AND ownership.workspace_id = calendar_adoption.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_adoption.source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_fact_binding_effective_owner_update
    ON calendar_knowledge_fact_binding FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_knowledge_fact_binding.plan_id
              AND ownership.workspace_id =
                    calendar_knowledge_fact_binding.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_knowledge_fact_binding.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_annotation_binding_effective_owner_update
    ON calendar_knowledge_annotation_binding FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_knowledge_annotation_binding.plan_id
              AND ownership.workspace_id =
                    calendar_knowledge_annotation_binding.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_knowledge_annotation_binding.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_attachment_effective_owner_update
    ON calendar_attachment_binding FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_attachment_binding.plan_id
              AND ownership.workspace_id =
                    calendar_attachment_binding.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_attachment_binding.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

CREATE POLICY rls_calendar_excerpt_effective_owner_update
    ON calendar_knowledge_excerpt FOR UPDATE
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_knowledge_excerpt.plan_id
              AND ownership.workspace_id =
                    calendar_knowledge_excerpt.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_knowledge_excerpt.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (app_workspace_matches(workspace_id));

DROP POLICY rls_calendar_plan_actor ON calendar_plan;
CREATE POLICY rls_calendar_plan_owner_insert
    ON calendar_plan FOR INSERT
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

DROP POLICY rls_calendar_activity_actor ON calendar_activity;
DROP POLICY rls_calendar_time_node_actor ON calendar_time_node;
DROP POLICY rls_calendar_online_link_actor
    ON calendar_online_access_link;

DROP POLICY rls_calendar_share_owner ON calendar_share;
CREATE POLICY rls_calendar_share_effective_owner
    ON calendar_share FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share.plan_id
              AND ownership.workspace_id = calendar_share.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share.plan_id
              AND ownership.workspace_id = calendar_share.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_content_grant_owner
    ON calendar_share_content_grant;
CREATE POLICY rls_calendar_content_grant_effective_owner
    ON calendar_share_content_grant FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_content_grant.plan_id
              AND ownership.workspace_id =
                    calendar_share_content_grant.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_content_grant.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1 FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_content_grant.plan_id
              AND ownership.workspace_id =
                    calendar_share_content_grant.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_content_grant.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_share_outbox_owner
    ON calendar_share_outbox;
CREATE POLICY rls_calendar_share_outbox_effective_owner
    ON calendar_share_outbox FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            JOIN calendar_plan_ownership ownership
              ON ownership.plan_id = share_row.plan_id
             AND ownership.workspace_id = share_row.workspace_id
             AND ownership.source_created_by_user_id =
                    share_row.created_by_user_id
            WHERE share_row.id = calendar_share_outbox.share_id
              AND share_row.workspace_id =
                    calendar_share_outbox.workspace_id
              AND share_row.created_by_user_id =
                    calendar_share_outbox.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_share share_row
            JOIN calendar_plan_ownership ownership
              ON ownership.plan_id = share_row.plan_id
             AND ownership.workspace_id = share_row.workspace_id
             AND ownership.source_created_by_user_id =
                    share_row.created_by_user_id
            WHERE share_row.id = calendar_share_outbox.share_id
              AND share_row.workspace_id =
                    calendar_share_outbox.workspace_id
              AND share_row.created_by_user_id =
                    calendar_share_outbox.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_share_scope_snapshot_owner
    ON calendar_share_scope_snapshot;

CREATE POLICY rls_calendar_share_scope_snapshot_effective_owner
    ON calendar_share_scope_snapshot
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_scope_snapshot.plan_id
              AND ownership.workspace_id =
                    calendar_share_scope_snapshot.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_scope_snapshot.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_scope_snapshot.plan_id
              AND ownership.workspace_id =
                    calendar_share_scope_snapshot.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_scope_snapshot.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_share_scope_item_owner
    ON calendar_share_scope_item;

CREATE POLICY rls_calendar_share_scope_item_effective_owner
    ON calendar_share_scope_item
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share_scope_item.plan_id
              AND ownership.workspace_id =
                    calendar_share_scope_item.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_scope_item.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share_scope_item.plan_id
              AND ownership.workspace_id =
                    calendar_share_scope_item.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_scope_item.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_share_request_receipt_owner
    ON calendar_share_request_receipt;

CREATE POLICY rls_calendar_share_request_receipt_effective_owner
    ON calendar_share_request_receipt
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_request_receipt.plan_id
              AND ownership.workspace_id =
                    calendar_share_request_receipt.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_request_receipt.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_share_request_receipt.plan_id
              AND ownership.workspace_id =
                    calendar_share_request_receipt.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_request_receipt.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_share_role_audit_owner
    ON calendar_share_role_audit;

CREATE POLICY rls_calendar_share_role_audit_effective_owner
    ON calendar_share_role_audit
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share_role_audit.plan_id
              AND ownership.workspace_id =
                    calendar_share_role_audit.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_role_audit.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = calendar_share_role_audit.plan_id
              AND ownership.workspace_id =
                    calendar_share_role_audit.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_share_role_audit.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_authoritative_capability_owner
    ON calendar_authoritative_editor_capability;

CREATE POLICY rls_calendar_authoritative_capability_effective_owner
    ON calendar_authoritative_editor_capability
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_authoritative_editor_capability.plan_id
              AND ownership.workspace_id =
                    calendar_authoritative_editor_capability.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_authoritative_editor_capability.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_authoritative_editor_capability.plan_id
              AND ownership.workspace_id =
                    calendar_authoritative_editor_capability.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_authoritative_editor_capability.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

DROP POLICY rls_calendar_adoption_source_lifecycle_owner
    ON calendar_adoption_source_lifecycle;

CREATE POLICY rls_calendar_adoption_source_lifecycle_effective_owner
    ON calendar_adoption_source_lifecycle
    FOR ALL
    USING (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_adoption_source_lifecycle.plan_id
              AND ownership.workspace_id =
                    calendar_adoption_source_lifecycle.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_adoption_source_lifecycle
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)))
    WITH CHECK (
        app_workspace_matches(workspace_id)
        AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id =
                    calendar_adoption_source_lifecycle.plan_id
              AND ownership.workspace_id =
                    calendar_adoption_source_lifecycle.workspace_id
              AND ownership.source_created_by_user_id =
                    calendar_adoption_source_lifecycle
                        .source_created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)));

CREATE OR REPLACE FUNCTION reject_calendar_editor_lock_row_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'calendar_plan'
       AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = OLD.id
              AND ownership.workspace_id = OLD.workspace_id
              AND ownership.source_created_by_user_id =
                    OLD.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)) THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'calendar_share'
       AND EXISTS (
            SELECT 1
            FROM calendar_plan_ownership ownership
            WHERE ownership.plan_id = OLD.plan_id
              AND ownership.workspace_id = OLD.workspace_id
              AND ownership.source_created_by_user_id =
                    OLD.created_by_user_id
              AND app_actor_matches(ownership.owner_user_id)) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'calendar editor lock rows are immutable';
END;
$$;
