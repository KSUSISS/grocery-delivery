ALTER TABLE delivery_assignments ADD COLUMN confirmed_meters integer
    CHECK (confirmed_meters BETWEEN 0 AND 1000000);
ALTER TABLE delivery_assignments ADD COLUMN delivered_at timestamptz;
