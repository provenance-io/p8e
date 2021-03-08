CREATE TABLE index_event_pump_request (
    uuid UUID NOT NULL PRIMARY KEY,
    created TIMESTAMPTZ NOT NULL
);

CREATE TABLE index_scope (
    uuid UUID NOT NULL PRIMARY KEY,
    scope_uuid UUID NOT NULL,
    scope BYTEA NOT NULL,
    created TIMESTAMPTZ NOT NULL
);

CREATE INDEX index_scope_scope_uuid_idx on index_scope (scope_uuid);

CREATE TABLE index_scope_public_key (
    uuid UUID NOT NULL PRIMARY KEY,
    index_scope_uuid UUID REFERENCES index_scope (uuid),
    public_key BYTEA NOT NULL,
    created TIMESTAMPTZ NOT NULL
);

CREATE INDEX index_scope_public_key_index_scope_uuid_idx on index_scope_public_key (index_scope_uuid);
CREATE INDEX index_scope_public_key_public_key_idx on index_scope_public_key (public_key);