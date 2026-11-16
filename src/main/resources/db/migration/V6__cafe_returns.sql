CREATE TABLE courier_returns (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shift_id bigint NOT NULL REFERENCES courier_shifts(id),
    last_order_id bigint NOT NULL UNIQUE REFERENCES orders(id),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    returned_at timestamptz,
    confirmed_meters integer CHECK (confirmed_meters BETWEEN 0 AND 1000000),
    confirmed_by bigint REFERENCES app_users(id),
    CHECK (
        (returned_at IS NULL AND confirmed_meters IS NULL AND confirmed_by IS NULL)
        OR
        (returned_at IS NOT NULL AND confirmed_meters IS NOT NULL AND confirmed_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_shift_pending_return ON courier_returns(shift_id) WHERE returned_at IS NULL;
CREATE INDEX ix_courier_returns_report ON courier_returns(shift_id, returned_at);

