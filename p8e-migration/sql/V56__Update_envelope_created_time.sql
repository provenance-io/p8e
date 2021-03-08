UPDATE envelope SET created_time = (data->'auditFields'->>'createdDate')::TIMESTAMPTZ WHERE created_time IS NULL;

ALTER TABLE envelope ALTER COLUMN created_time SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE envelope ALTER COLUMN created_time SET NOT NULL;