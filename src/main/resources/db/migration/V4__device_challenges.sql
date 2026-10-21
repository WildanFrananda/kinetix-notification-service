CREATE TABLE notification_device_challenges (
    device_token VARCHAR(512) NOT NULL,
    claimant_principal_id VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    nonce_digest CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_token, claimant_principal_id),
    CONSTRAINT notification_device_challenges_platform_known
        CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT notification_device_challenges_digest_is_sha256_hex
        CHECK (nonce_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notification_device_challenges_attempts_counted
        CHECK (failed_attempts >= 0)
);

CREATE INDEX idx_notification_device_challenges_expiry
    ON notification_device_challenges (expires_at);

CREATE INDEX idx_notification_devices_principal_recency
    ON notification_devices (principal_id, registered_at DESC);
