ALTER TABLE delivery_assignments ADD COLUMN queue_position bigint;
UPDATE delivery_assignments SET queue_position = id;
ALTER TABLE delivery_assignments ALTER COLUMN queue_position SET NOT NULL;
ALTER TABLE delivery_assignments ADD CONSTRAINT ck_queue_position CHECK (queue_position > 0);
DROP INDEX uq_shift_active_assignment;
CREATE UNIQUE INDEX uq_shift_queue_position ON delivery_assignments(shift_id, queue_position)
    WHERE released_at IS NULL;
