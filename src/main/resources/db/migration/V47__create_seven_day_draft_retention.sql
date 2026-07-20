CREATE TABLE draft_retention_preference (
    id                           BIGSERIAL PRIMARY KEY,
    workspace_id                 UUID        NOT NULL REFERENCES workspace (id),
    created_by_user_id           UUID        NOT NULL REFERENCES app_user (id),
    default_retention_days       INTEGER     NOT NULL DEFAULT 7,
    default_reminder_days_before INTEGER     NOT NULL DEFAULT 0,
    default_reminder_time        TIME        NOT NULL DEFAULT TIME '20:00:00',
    settings_confirmed           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_draft_retention_preference_actor
        UNIQUE (workspace_id, created_by_user_id),
    CONSTRAINT ck_draft_default_retention_days
        CHECK (default_retention_days BETWEEN 1 AND 30),
    CONSTRAINT ck_draft_default_reminder_days
        CHECK (default_reminder_days_before BETWEEN 0 AND default_retention_days),
    CONSTRAINT ck_draft_default_reminder_latest
        CHECK (default_reminder_time <= TIME '23:00:00')
);

CREATE TABLE draft_retention_binding (
    id                          BIGSERIAL PRIMARY KEY,
    workspace_id                UUID        NOT NULL REFERENCES workspace (id),
    created_by_user_id          UUID        NOT NULL REFERENCES app_user (id),
    draft_type                  VARCHAR(40) NOT NULL,
    draft_id                    BIGINT      NOT NULL,
    title                       VARCHAR(240) NOT NULL,
    last_edited_at              TIMESTAMPTZ NOT NULL,
    uses_default_retention      BOOLEAN     NOT NULL,
    custom_retention_days       INTEGER,
    uses_default_reminder       BOOLEAN     NOT NULL,
    custom_reminder_days_before INTEGER,
    custom_reminder_time        TIME,
    expires_at                  TIMESTAMPTZ NOT NULL,
    remind_at                   TIMESTAMPTZ NOT NULL,
    notified_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_draft_retention_binding
        UNIQUE (workspace_id, created_by_user_id, draft_type, draft_id),
    CONSTRAINT ck_draft_retention_type
        CHECK (draft_type IN ('BANK_TRANSFER', 'PRODUCT_OBSERVATION')),
    CONSTRAINT ck_draft_custom_retention
        CHECK ((uses_default_retention AND custom_retention_days IS NULL)
            OR (NOT uses_default_retention AND custom_retention_days BETWEEN 1 AND 30)),
    CONSTRAINT ck_draft_custom_reminder
        CHECK ((uses_default_reminder
                AND custom_reminder_days_before IS NULL AND custom_reminder_time IS NULL)
            OR (NOT uses_default_reminder
                AND custom_reminder_days_before >= 0
                AND custom_reminder_time <= TIME '23:00:00')),
    CONSTRAINT ck_draft_effective_times
        CHECK (remind_at > last_edited_at AND remind_at < expires_at)
);

CREATE INDEX idx_draft_retention_actor_reminder
    ON draft_retention_binding (workspace_id, created_by_user_id, remind_at)
    WHERE notified_at IS NULL;
CREATE INDEX idx_draft_retention_actor_expiry
    ON draft_retention_binding (workspace_id, created_by_user_id, expires_at);

ALTER TABLE draft_retention_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE draft_retention_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_draft_retention_preference_actor ON draft_retention_preference FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

ALTER TABLE draft_retention_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE draft_retention_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_draft_retention_binding_actor ON draft_retention_binding FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));

-- Existing pending drafts adopt the initial defaults. The seventh day ends at 24:00,
-- so deletion is the next day's 00:00 in Asia/Taipei rather than a rolling 168 hours.
INSERT INTO draft_retention_preference (
    workspace_id, created_by_user_id, default_retention_days,
    default_reminder_days_before, default_reminder_time, settings_confirmed,
    created_at, updated_at)
SELECT workspace_id, created_by_user_id, 7, 0, TIME '20:00:00', FALSE,
       MIN(created_at), MAX(updated_at)
FROM (
    SELECT workspace_id, created_by_user_id, created_at, updated_at
    FROM bank_transfer_draft WHERE status = 'PENDING_RECIPIENT'
    UNION ALL
    SELECT workspace_id, created_by_user_id, created_at, updated_at
    FROM product_observation_draft WHERE status = 'PENDING_PURPOSE'
) pending
GROUP BY workspace_id, created_by_user_id
ON CONFLICT (workspace_id, created_by_user_id) DO NOTHING;

INSERT INTO draft_retention_binding (
    workspace_id, created_by_user_id, draft_type, draft_id, title, last_edited_at,
    uses_default_retention, custom_retention_days,
    uses_default_reminder, custom_reminder_days_before, custom_reminder_time,
    expires_at, remind_at, created_at, updated_at)
SELECT workspace_id, created_by_user_id, 'BANK_TRANSFER', id,
       COALESCE(NULLIF(purpose, ''), '轉帳草稿'), updated_at,
       TRUE, NULL, TRUE, NULL, NULL,
       (((updated_at AT TIME ZONE 'Asia/Taipei')::date + 8)::timestamp
           AT TIME ZONE 'Asia/Taipei'),
       (((updated_at AT TIME ZONE 'Asia/Taipei')::date + 7)
           + TIME '20:00:00') AT TIME ZONE 'Asia/Taipei',
       created_at, updated_at
FROM bank_transfer_draft WHERE status = 'PENDING_RECIPIENT';

INSERT INTO draft_retention_binding (
    workspace_id, created_by_user_id, draft_type, draft_id, title, last_edited_at,
    uses_default_retention, custom_retention_days,
    uses_default_reminder, custom_reminder_days_before, custom_reminder_time,
    expires_at, remind_at, created_at, updated_at)
SELECT workspace_id, created_by_user_id, 'PRODUCT_OBSERVATION', id,
       COALESCE(NULLIF(product_name, ''), NULLIF(brand_name, ''), '產品觀察草稿'), updated_at,
       TRUE, NULL, TRUE, NULL, NULL,
       (((updated_at AT TIME ZONE 'Asia/Taipei')::date + 8)::timestamp
           AT TIME ZONE 'Asia/Taipei'),
       (((updated_at AT TIME ZONE 'Asia/Taipei')::date + 7)
           + TIME '20:00:00') AT TIME ZONE 'Asia/Taipei',
       created_at, updated_at
FROM product_observation_draft WHERE status = 'PENDING_PURPOSE';

UPDATE bank_transfer_draft draft
SET expires_at = binding.expires_at
FROM draft_retention_binding binding
WHERE binding.draft_type = 'BANK_TRANSFER' AND binding.draft_id = draft.id
  AND binding.workspace_id = draft.workspace_id
  AND binding.created_by_user_id = draft.created_by_user_id;

UPDATE product_observation_draft draft
SET expires_at = binding.expires_at
FROM draft_retention_binding binding
WHERE binding.draft_type = 'PRODUCT_OBSERVATION' AND binding.draft_id = draft.id
  AND binding.workspace_id = draft.workspace_id
  AND binding.created_by_user_id = draft.created_by_user_id;
