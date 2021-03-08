ALTER TABLE envelope ADD CONSTRAINT execution_unique UNIQUE (execution_uuid, public_key);
