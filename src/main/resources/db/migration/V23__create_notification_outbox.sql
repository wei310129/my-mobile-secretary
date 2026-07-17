-- Transactional notification outbox.
-- `destination` is an opaque, non-secret routing reference. Provider credentials/device tokens
-- stay in the channel adapter; they must never be copied into this table.
CREATE TABLE notification_outbox (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_user (id),
    delivery_id        UUID         NOT NULL,
    delivery_key       VARCHAR(250) NOT NULL,
    target_user_id     UUID         NOT NULL REFERENCES app_user (id),
    channel            VARCHAR(20)  NOT NULL,
    destination        VARCHAR(500) NOT NULL,
    reminder_id        BIGINT       REFERENCES reminder (id),
    task_id            BIGINT       REFERENCES task (id),
    title              VARCHAR(200),
    message            TEXT,
    retry_safe         BOOLEAN      NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    available_at       TIMESTAMPTZ  NOT NULL,
    claimed_until      TIMESTAMPTZ,
    claim_token        UUID,
    last_error         VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL,
    sent_at            TIMESTAMPTZ,
    terminal_at        TIMESTAMPTZ,
    CONSTRAINT uq_notification_outbox_delivery_id UNIQUE (delivery_id),
    CONSTRAINT uq_notification_outbox_logical_delivery
        UNIQUE (workspace_id, delivery_key, channel),
    CONSTRAINT ck_notification_outbox_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DEAD_LETTER')),
    CONSTRAINT ck_notification_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_outbox_destination CHECK (length(trim(destination)) > 0),
    CONSTRAINT ck_notification_outbox_claim
        CHECK ((status = 'SENDING' AND claimed_until IS NOT NULL AND claim_token IS NOT NULL)
            OR (status <> 'SENDING' AND claimed_until IS NULL AND claim_token IS NULL)),
    CONSTRAINT ck_notification_outbox_payload_lifecycle
        CHECK ((status IN ('PENDING', 'SENDING') AND title IS NOT NULL AND message IS NOT NULL
                AND terminal_at IS NULL)
            OR (status IN ('SENT', 'DEAD_LETTER') AND title IS NULL AND message IS NULL
                AND terminal_at IS NOT NULL))
);

CREATE INDEX idx_notification_outbox_claim
    ON notification_outbox (workspace_id, status, available_at, created_at);
CREATE INDEX idx_notification_outbox_expired_claim
    ON notification_outbox (workspace_id, claimed_until)
    WHERE status = 'SENDING';
CREATE INDEX idx_notification_outbox_target
    ON notification_outbox (workspace_id, target_user_id, created_at DESC);
