CREATE TABLE conditional_venue_draft (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id  UUID         NOT NULL REFERENCES app_user (id),
    title               VARCHAR(200) NOT NULL,
    event_start_at      TIMESTAMPTZ  NOT NULL,
    event_end_at        TIMESTAMPTZ  NOT NULL,
    primary_place_name  VARCHAR(200) NOT NULL,
    fallback_place_name VARCHAR(200) NOT NULL,
    decision_at         TIMESTAMPTZ  NOT NULL,
    decision_task_id    BIGINT       NOT NULL REFERENCES task (id),
    status              VARCHAR(20)  NOT NULL,
    selected_place_name VARCHAR(200),
    schedule_item_id    BIGINT REFERENCES schedule_item (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_conditional_venue_time_order
        CHECK (decision_at < event_start_at AND event_end_at > event_start_at),
    CONSTRAINT ck_conditional_venue_distinct_places
        CHECK (primary_place_name <> fallback_place_name),
    CONSTRAINT ck_conditional_venue_status
        CHECK (status IN ('PENDING', 'RESOLVED', 'CANCELED')),
    CONSTRAINT ck_conditional_venue_resolution
        CHECK ((status = 'PENDING' AND selected_place_name IS NULL AND schedule_item_id IS NULL)
            OR (status = 'RESOLVED' AND selected_place_name IS NOT NULL AND schedule_item_id IS NOT NULL)
            OR status = 'CANCELED')
);

CREATE INDEX idx_conditional_venue_actor_pending
    ON conditional_venue_draft (workspace_id, created_by_user_id, created_at DESC)
    WHERE status = 'PENDING';

ALTER TABLE conditional_venue_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE conditional_venue_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_conditional_venue_draft_actor ON conditional_venue_draft FOR ALL
    USING (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id) AND app_actor_matches(created_by_user_id));
