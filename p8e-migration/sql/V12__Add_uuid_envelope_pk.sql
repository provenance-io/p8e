CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Need to drop constraint to do stuff
ALTER TABLE envelope DROP CONSTRAINT envelope_scope_fk;

-- Add new pk for scope
ALTER TABLE scope DROP CONSTRAINT scope_pkey;
ALTER TABLE scope ADD COLUMN uuid UUID NOT NULL DEFAULT uuid_generate_v1();
ALTER TABLE scope ADD PRIMARY KEY (uuid);

-- Add new pk for envelope
ALTER TABLE envelope DROP CONSTRAINT envelope_pkey;
ALTER TABLE envelope ADD COLUMN uuid UUID NOT NULL DEFAULT uuid_generate_v1();
ALTER TABLE envelope ADD PRIMARY KEY (uuid);

-- Re-attach envelope to scope by uuid
UPDATE envelope
  SET scope_uuid = subquery.uuid
  FROM (SELECT s.uuid, s.scope_uuid FROM envelope e INNER JOIN scope s ON e.scope_uuid = s.scope_uuid) AS subquery
  WHERE envelope.scope_uuid = subquery.scope_uuid;

-- Re-enable fk
ALTER TABLE envelope ADD CONSTRAINT envelope_scope_fk FOREIGN KEY (scope_uuid) REFERENCES scope (uuid);

-- Add some indexes
CREATE INDEX envelope_artifact_uuid_idx ON envelope (artifact_uuid);
CREATE INDEX scope_scope_uuid_idx ON scope (scope_uuid);