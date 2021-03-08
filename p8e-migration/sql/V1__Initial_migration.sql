CREATE TABLE IF NOT EXISTS scope(
  scope_uuid UUID PRIMARY KEY,
  data JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS envelope(
  artifact_uuid UUID PRIMARY KEY,
  scope_uuid UUID NOT NULL,
  data JSONB NOT NULL
);

ALTER TABLE envelope ADD CONSTRAINT envelope_scope_fk FOREIGN KEY (scope_uuid) REFERENCES scope (scope_uuid);
