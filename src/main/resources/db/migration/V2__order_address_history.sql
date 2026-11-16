CREATE TABLE order_address_changes (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    changed_by bigint NOT NULL REFERENCES app_users(id),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    previous_address varchar(500) NOT NULL,
    previous_type varchar(16) NOT NULL CHECK (previous_type IN ('APARTMENT', 'PRIVATE_HOUSE')),
    new_address varchar(500) NOT NULL CHECK (length(btrim(new_address)) > 0),
    new_type varchar(16) NOT NULL CHECK (new_type IN ('APARTMENT', 'PRIVATE_HOUSE')),
    reason varchar(500) NOT NULL CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX ix_order_address_changes_order ON order_address_changes(order_id, id);
