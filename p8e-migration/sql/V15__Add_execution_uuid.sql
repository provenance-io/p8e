ALTER TABLE envelope ADD COLUMN execution_uuid UUID;
UPDATE envelope SET execution_uuid = artifact_uuid WHERE execution_uuid IS NULL;
ALTER TABLE envelope ALTER COLUMN execution_uuid SET NOT NULL;
CREATE INDEX envelope_execution_uuid_idx on envelope (execution_uuid);

DROP INDEX proto_message_proposed_artifact_uuid_idx;
ALTER TABLE proto_message RENAME COLUMN proposed_artifact_uuid TO proposed_execution_uuid;
CREATE INDEX proto_message_proposed_execution_uuid_idx on proto_message (proposed_execution_uuid);
