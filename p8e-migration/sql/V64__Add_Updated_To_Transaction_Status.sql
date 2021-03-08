BEGIN;
ALTER TABLE transaction_status ADD COLUMN updated TIMESTAMPTZ;
UPDATE transaction_status SET updated = created;
ALTER TABLE transaction_status ALTER COLUMN updated SET NOT NULL;
ALTER TABLE transaction_status ALTER COLUMN updated SET DEFAULT CURRENT_TIMESTAMP;
COMMIT;
