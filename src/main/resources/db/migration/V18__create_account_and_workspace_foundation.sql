-- Multi-tenant foundation. RLS is intentionally enabled in a later migration,
-- after every application entry point can establish a trustworthy workspace context.

CREATE TABLE app_user (
    id           UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE external_identity (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user (id),
    provider   VARCHAR(50)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_external_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_external_identity_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX idx_external_identity_user ON external_identity (user_id);

CREATE TABLE workspace (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(120) NOT NULL,
    type               VARCHAR(20)  NOT NULL,
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_workspace_type CHECK (type IN ('PERSONAL', 'HOUSEHOLD'))
);

CREATE UNIQUE INDEX uq_workspace_personal_creator
    ON workspace (created_by_user_id)
    WHERE type = 'PERSONAL';

CREATE TABLE workspace_member (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID        NOT NULL REFERENCES workspace (id),
    user_id            UUID        NOT NULL REFERENCES app_user (id),
    role               VARCHAR(20) NOT NULL,
    created_by_user_id UUID        NOT NULL REFERENCES app_user (id),
    joined_at          TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_workspace_member_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT chk_workspace_member_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX idx_workspace_member_user ON workspace_member (user_id, workspace_id);

-- Stable compatibility records receive all pre-workspace data. These identifiers are
-- constants in LegacyAccountIds and must never be reassigned to a real external identity.
INSERT INTO app_user (id, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Legacy Owner', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000101', 'Personal', 'PERSONAL',
        '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO workspace_member (id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000201',
        '00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000001',
        'OWNER',
        '00000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Add tenant ownership to every table created by V2-V17. Adding nullable columns first,
-- backfilling, and only then applying NOT NULL preserves every legacy row.
DO $$
DECLARE
    table_name TEXT;
    business_tables CONSTANT TEXT[] := ARRAY[
        'task',
        'place',
        'geofence_rule',
        'reminder',
        'location_event',
        'reminder_delivery',
        'item',
        'item_place',
        'schedule_item',
        'schedule_follow_up',
        'schedule_outcome',
        'buffer_rule',
        'price_record',
        'intent_issue',
        'line_message_log',
        'conversation_context',
        'place_alias',
        'planning_preference',
        'reminder_preference'
    ];
BEGIN
    FOREACH table_name IN ARRAY business_tables LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN workspace_id UUID', table_name);
        EXECUTE format('ALTER TABLE %I ADD COLUMN created_by_user_id UUID', table_name);

        EXECUTE format(
            'UPDATE %I SET workspace_id = %L, created_by_user_id = %L',
            table_name,
            '00000000-0000-0000-0000-000000000101',
            '00000000-0000-0000-0000-000000000001'
        );

        EXECUTE format('ALTER TABLE %I ALTER COLUMN workspace_id SET NOT NULL', table_name);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN created_by_user_id SET NOT NULL', table_name);
        -- Temporary compatibility defaults keep V2-V17 entities writable until their
        -- workspace fields are mapped. A later migration removes both defaults after
        -- WorkspaceContext is mandatory at every write boundary.
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN workspace_id SET DEFAULT %L::uuid',
            table_name,
            '00000000-0000-0000-0000-000000000101'
        );
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN created_by_user_id SET DEFAULT %L::uuid',
            table_name,
            '00000000-0000-0000-0000-000000000001'
        );
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (workspace_id) REFERENCES workspace (id)',
            table_name,
            'fk_' || table_name || '_workspace'
        );
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (created_by_user_id) REFERENCES app_user (id)',
            table_name,
            'fk_' || table_name || '_creator'
        );
        EXECUTE format(
            'CREATE INDEX %I ON %I (workspace_id)',
            'idx_' || table_name || '_workspace',
            table_name
        );
    END LOOP;
END $$;

-- Natural-key uniqueness belongs to a workspace, not to the entire installation.
ALTER TABLE item DROP CONSTRAINT IF EXISTS item_name_key;
ALTER TABLE item
    ADD CONSTRAINT uq_item_workspace_name UNIQUE (workspace_id, name);

ALTER TABLE place_alias DROP CONSTRAINT IF EXISTS place_alias_alias_key;
ALTER TABLE place_alias
    ADD CONSTRAINT uq_place_alias_workspace_alias UNIQUE (workspace_id, alias);

-- Relationship uniqueness is tenant-scoped as well. The referenced ids are still global
-- today, but these constraints remain correct if ids become tenant-local in the future.
ALTER TABLE schedule_follow_up DROP CONSTRAINT IF EXISTS schedule_follow_up_schedule_item_id_key;
ALTER TABLE schedule_follow_up
    ADD CONSTRAINT uq_schedule_follow_up_workspace_item UNIQUE (workspace_id, schedule_item_id);

ALTER TABLE schedule_outcome DROP CONSTRAINT IF EXISTS schedule_outcome_schedule_item_id_key;
ALTER TABLE schedule_outcome
    ADD CONSTRAINT uq_schedule_outcome_workspace_item UNIQUE (workspace_id, schedule_item_id);

ALTER TABLE buffer_rule DROP CONSTRAINT IF EXISTS buffer_rule_place_id_key;
ALTER TABLE buffer_rule
    ADD CONSTRAINT uq_buffer_rule_workspace_place UNIQUE (workspace_id, place_id);

-- Keep the existing integer primary keys for compatibility while enforcing at most one
-- settings/context row per workspace; identity generation is enabled below for new tenants.
ALTER TABLE conversation_context
    ADD CONSTRAINT uq_conversation_context_workspace UNIQUE (workspace_id);
ALTER TABLE planning_preference
    ADD CONSTRAINT uq_planning_preference_workspace UNIQUE (workspace_id);
ALTER TABLE reminder_preference
    ADD CONSTRAINT uq_reminder_preference_workspace UNIQUE (workspace_id);

-- The original single-user implementation assigned id=1 to all three singleton records.
-- Keep existing ids, then let PostgreSQL allocate globally unique ids for additional tenants.
ALTER TABLE conversation_context ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
SELECT setval(pg_get_serial_sequence('conversation_context', 'id'),
              COALESCE((SELECT MAX(id) FROM conversation_context), 0) + 1, false);

ALTER TABLE planning_preference ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
SELECT setval(pg_get_serial_sequence('planning_preference', 'id'),
              COALESCE((SELECT MAX(id) FROM planning_preference), 0) + 1, false);

ALTER TABLE reminder_preference ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
SELECT setval(pg_get_serial_sequence('reminder_preference', 'id'),
              COALESCE((SELECT MAX(id) FROM reminder_preference), 0) + 1, false);
