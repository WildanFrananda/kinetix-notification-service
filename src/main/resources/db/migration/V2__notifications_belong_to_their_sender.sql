ALTER TABLE notifications ADD COLUMN sender VARCHAR(64) NOT NULL;

ALTER TABLE notifications DROP CONSTRAINT uq_notifications_idempotency_key;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_sender_idempotency_key UNIQUE (sender, idempotency_key);

ALTER TABLE notifications
    ADD CONSTRAINT notifications_template_known
        CHECK (template IN (
            'ORDER_PAID',
            'ORDER_PACKED',
            'COURIER_ASSIGNED',
            'ORDER_DELIVERED',
            'COURIER_OFFER',
            'RETURN_OPENED'
        ));
