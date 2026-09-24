CREATE TABLE notifications (
    id VARCHAR(64) PRIMARY KEY,
    recipient_principal_id VARCHAR(64) NOT NULL,
    template VARCHAR(48) NOT NULL,
    params JSONB NOT NULL DEFAULT '{}'::jsonb,
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
    PRIMARY KEY (notification_id, channel),
    CONSTRAINT notification_attempts_status_known
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'UNREACHABLE')),
    CONSTRAINT notification_attempts_channel_known
        CHECK (channel IN ('PUSH', 'EMAIL'))
);

CREATE TABLE notification_devices (
    device_token VARCHAR(512) PRIMARY KEY,
    principal_id VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notification_devices_platform_known
        CHECK (platform IN ('ANDROID', 'IOS'))
);

CREATE INDEX idx_notification_devices_principal ON notification_devices (principal_id);
