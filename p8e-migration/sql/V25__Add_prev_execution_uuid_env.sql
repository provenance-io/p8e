ALTER TABLE envelope ADD COLUMN prev_execution_uuid UUID;

CREATE INDEX envelope_prev_execution_uuid_idx on envelope (prev_execution_uuid);