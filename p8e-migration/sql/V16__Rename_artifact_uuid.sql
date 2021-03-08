DROP INDEX envelope_artifact_uuid_idx;
ALTER TABLE envelope RENAME COLUMN artifact_uuid TO group_uuid;
CREATE INDEX envelope_group_uuid_idx on envelope (group_uuid);