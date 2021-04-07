CREATE TABLE contract_spec_mapping(
  uuid UUID PRIMARY KEY,
  scope_specification_uuid UUID NOT NULL,
  hash TEXT NOT NULL,
  provenance_hash TEXT NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT fk_scope_specification FOREIGN KEY(scope_specification_uuid) REFERENCES scope_specification_definition(uuid)
);

CREATE UNIQUE INDEX contract_spec_hash_scope_unq_idx ON contract_spec_mapping (hash, scope_specification_uuid);
