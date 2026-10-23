CREATE TABLE notification_device_challenge_issues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_token VARCHAR(512) NOT NULL,
    claimant_principal_id VARCHAR(64) NOT NULL,
    nonce_digest CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_device_challenge_issues_digest_is_sha256_hex
        CHECK (nonce_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_notification_device_challenge_issues_token
    ON notification_device_challenge_issues (device_token, issued_at);

CREATE INDEX idx_notification_device_challenge_issues_age
    ON notification_device_challenge_issues (issued_at);
