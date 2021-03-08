ALTER TABLE scope ADD COLUMN last_execution_uuid UUID;

CREATE INDEX scope_last_execution_uuid_idx on scope (last_execution_uuid);