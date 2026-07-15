CREATE TABLE reminder_preference (
    id                  INT PRIMARY KEY,
    quiet_start         TIME,
    quiet_end           TIME,
    muted_until         TIMESTAMPTZ,
    allow_high_priority BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at          TIMESTAMPTZ NOT NULL
);
