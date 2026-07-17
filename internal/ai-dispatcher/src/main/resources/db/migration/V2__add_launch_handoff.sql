ALTER TABLE dispatcher_run
    ADD COLUMN launch_dispatched_at TIMESTAMPTZ,
    ADD COLUMN launch_owner_id VARCHAR(200);

ALTER TABLE dispatcher_run
    ADD CONSTRAINT chk_dispatcher_run_launch_handoff
        CHECK ((launch_dispatched_at IS NULL AND launch_owner_id IS NULL)
            OR (launch_dispatched_at IS NOT NULL AND launch_owner_id IS NOT NULL));
