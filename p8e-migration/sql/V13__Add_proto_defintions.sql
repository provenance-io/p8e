CREATE TABLE proto_message (
  proto_message_uuid UUID PRIMARY KEY,
  affiliate_uuid UUID NOT NULL,
  proposed_artifact_uuid UUID,
  class_name TEXT NOT NULL,
  hash TEXT NOT NULL,
  signature TEXT NOT NULL,
  created TIMESTAMPTZ NOT NULL
);

CREATE INDEX proto_message_affiliate_uuid_idx on proto_message (affiliate_uuid);
CREATE INDEX proto_message_proposed_artifact_uuid_idx on proto_message (proposed_artifact_uuid);
CREATE INDEX proto_message_hash_idx on proto_message (hash);
