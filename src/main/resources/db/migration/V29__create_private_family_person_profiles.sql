-- A family person is first a private identity in one actor's mental model.
-- Phase 5 may add a deliberate family read path, but private rows must never become shared
-- merely because the owner joins a family workspace.
CREATE TABLE family_person_profile (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    canonical_key      VARCHAR(80)  NOT NULL,
    role               VARCHAR(30)  NOT NULL,
    display_label      VARCHAR(80)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_family_person_profile_key
        UNIQUE (workspace_id, created_by_user_id, canonical_key),
    CONSTRAINT uq_family_person_profile_owner
        UNIQUE (id, workspace_id, created_by_user_id),
    CONSTRAINT chk_family_person_profile_role
        CHECK (role IN ('SPOUSE', 'DAUGHTER', 'SON', 'OTHER'))
);

CREATE TABLE family_person_alias (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    person_id          BIGINT       NOT NULL,
    alias              VARCHAR(80)  NOT NULL,
    normalized_alias   VARCHAR(80)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_family_person_alias_owner_alias
        UNIQUE (workspace_id, created_by_user_id, normalized_alias),
    CONSTRAINT fk_family_person_alias_owner
        FOREIGN KEY (person_id, workspace_id, created_by_user_id)
        REFERENCES family_person_profile (id, workspace_id, created_by_user_id)
        ON DELETE CASCADE
);

CREATE TABLE family_person_attribute (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    person_id          BIGINT       NOT NULL,
    attribute_key      VARCHAR(40)  NOT NULL,
    attribute_value    VARCHAR(500) NOT NULL,
    visibility         VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_family_person_attribute_key
        UNIQUE (workspace_id, created_by_user_id, person_id, attribute_key),
    CONSTRAINT fk_family_person_attribute_owner
        FOREIGN KEY (person_id, workspace_id, created_by_user_id)
        REFERENCES family_person_profile (id, workspace_id, created_by_user_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_family_person_attribute_visibility
        CHECK (visibility IN ('PRIVATE', 'FAMILY')),
    -- Sharing is opt-in and fail-closed. Medical or free-form facts are deliberately absent.
    CONSTRAINT chk_family_person_attribute_family_allowlist
        CHECK (visibility = 'PRIVATE' OR attribute_key IN (
            'NAME', 'GENDER', 'HEIGHT_CM', 'WEIGHT_KG', 'BIRTH_DATE',
            'SCHOOL', 'COMPANY', 'WORKPLACE'
        ))
);

CREATE INDEX idx_family_person_profile_actor
    ON family_person_profile (workspace_id, created_by_user_id, updated_at DESC);
CREATE INDEX idx_family_person_alias_person
    ON family_person_alias (workspace_id, created_by_user_id, person_id);
CREATE INDEX idx_family_person_attribute_person
    ON family_person_attribute (workspace_id, created_by_user_id, person_id);

ALTER TABLE family_person_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE family_person_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_family_person_profile_actor ON family_person_profile
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE family_person_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE family_person_alias FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_family_person_alias_actor ON family_person_alias
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));

ALTER TABLE family_person_attribute ENABLE ROW LEVEL SECURITY;
ALTER TABLE family_person_attribute FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_family_person_attribute_actor ON family_person_attribute
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
