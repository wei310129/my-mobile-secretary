ALTER TABLE dispatcher_lane
    ADD COLUMN coordinator_drain_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE dispatcher_drain_audit (
    audit_id UUID PRIMARY KEY,
    action VARCHAR(20) NOT NULL,
    actor_id VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
