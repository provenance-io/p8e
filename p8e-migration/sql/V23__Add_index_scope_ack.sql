CREATE TABLE index_scope_ack (
    uuid UUID NOT NULL PRIMARY KEY,
    index_scope_uuid UUID NOT NULL REFERENCES index_scope (uuid),
    public_key BYTEA NOT NULL,
    acked_at TIMESTAMPTZ
);

CREATE INDEX index_scope_ack_public_key_idx on index_scope_ack (public_key);
CREATE INDEX index_scope_ack_acked_at_idx on index_scope_ack (acked_at);