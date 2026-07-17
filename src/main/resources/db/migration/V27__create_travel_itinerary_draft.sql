-- Extracted travel documents are private actor drafts. They are never converted into business
-- schedules merely because an AI read an image; the user must confirm the normalized preview.
CREATE TABLE travel_itinerary_draft (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    title              VARCHAR(160) NOT NULL,
    payload            TEXT         NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_travel_itinerary_draft_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'DISCARDED')),
    CONSTRAINT chk_travel_itinerary_draft_payload_size
        CHECK (char_length(payload) <= 30000)
);

CREATE INDEX idx_travel_itinerary_draft_actor_status
    ON travel_itinerary_draft (workspace_id, created_by_user_id, status, created_at DESC);
CREATE INDEX idx_travel_itinerary_draft_expiry
    ON travel_itinerary_draft (expires_at)
    WHERE status = 'PENDING';

ALTER TABLE travel_itinerary_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE travel_itinerary_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_travel_itinerary_draft_actor ON travel_itinerary_draft
    FOR ALL
    USING (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id))
    WITH CHECK (app_workspace_matches(workspace_id)
        AND app_actor_matches(created_by_user_id));
CREATE POLICY rls_travel_itinerary_draft_system_delete ON travel_itinerary_draft
    FOR DELETE
    USING (app_current_scope() = 'SYSTEM');
