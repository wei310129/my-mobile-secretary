-- Split a new reservation from the point where a business mutation may have committed.
-- UNKNOWN is fail-closed: a duplicate delivery must never execute it again.
ALTER TABLE idempotency_record
    DROP CONSTRAINT chk_idempotency_status;

-- Existing IN_PROGRESS rows predate the execution boundary. Conservatively treating them as
-- unknown avoids replaying a mutation that may already have committed during an older process.
UPDATE idempotency_record
SET status = 'UNKNOWN',
    response_action = COALESCE(response_action, 'MIGRATED_IN_PROGRESS')
WHERE status = 'IN_PROGRESS';

ALTER TABLE idempotency_record
    ADD CONSTRAINT chk_idempotency_status
        CHECK (status IN ('RESERVED', 'UNKNOWN', 'COMPLETED', 'FAILED'));
