CREATE TABLE definition (
  definition_uuid UUID PRIMARY KEY,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  hash TEXT NOT NULL,
  signature TEXT NOT NULL,
  created TIMESTAMPTZ NOT NULL,
  updated TIMESTAMPTZ
);

CREATE UNIQUE INDEX definition_target_type_target_id_idx ON definition (target_type, target_id);
