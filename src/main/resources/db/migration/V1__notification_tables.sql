-- The delivery record, and the handsets a push can reach.
--
-- What is deliberately absent: email addresses and telephone numbers. Identity holds those, and
-- this service asks at send time. A copy here would be a third place a person's contact details
-- live and a third place they leak from — which is the fault the platform's boundary-debt register
-- keeps finding in other services.

CREATE TABLE notifications (
    id VARCHAR(64) PRIMARY KEY,
    recipient_principal_id VARCHAR(64) NOT NULL,
    template VARCHAR(48) NOT NULL,
    -- The template's facts, never a person: an order number, a tracking number, an amount.
    params JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- The caller's name for "this same notification". Unique, because that is the whole mechanism:
    -- a retried RPC hits this constraint instead of sending a second message.
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notifications_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_principal_id, created_at DESC);

CREATE TABLE notification_attempts (
    notification_id VARCHAR(64) NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- One row per channel per notification. A retry raises the count on the row it already has;
    -- a second row would make one notification look like two.
    PRIMARY KEY (notification_id, channel),
    CONSTRAINT notification_attempts_status_known
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'UNREACHABLE')),
    CONSTRAINT notification_attempts_channel_known
        CHECK (channel IN ('PUSH', 'EMAIL'))
);

CREATE TABLE notification_devices (
    -- The token is the key, not (principal, token): one handset is one device, and when it changes
    -- hands the row moves rather than doubling. That is what stops a push landing on the previous
    -- owner's screen.
    device_token VARCHAR(512) PRIMARY KEY,
    principal_id VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notification_devices_platform_known
        CHECK (platform IN ('ANDROID', 'IOS'))
);

CREATE INDEX idx_notification_devices_principal ON notification_devices (principal_id);
