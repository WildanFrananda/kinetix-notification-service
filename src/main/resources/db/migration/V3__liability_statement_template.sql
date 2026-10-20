ALTER TABLE notifications DROP CONSTRAINT notifications_template_known;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_template_known
        CHECK (template IN (
            'ORDER_PAID',
            'ORDER_PACKED',
            'COURIER_ASSIGNED',
            'ORDER_DELIVERED',
            'COURIER_OFFER',
            'RETURN_OPENED',
            'LIABILITY_STATEMENT'
        ));
