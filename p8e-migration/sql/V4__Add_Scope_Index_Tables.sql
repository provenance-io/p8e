CREATE TABLE index_scope_fragment (
    uuid UUID NOT NULL PRIMARY KEY,
    index_scope_uuid UUID NOT NULL REFERENCES index_scope (uuid),
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    path TEXT NOT NULL,
    type TEXT NOT NULL,
    parent_type TEXT NOT NULL,
    created TIMESTAMPTZ NOT NULL
);

CREATE INDEX index_scope_index_index_scope_uuid_idx on index_scope_fragment (index_scope_uuid);
CREATE INDEX index_scope_index_name_idx on index_scope_fragment (name);
CREATE INDEX index_scope_index_value_idx on index_scope_fragment (value);
CREATE INDEX index_scope_index_path_idx on index_scope_fragment (path);
CREATE INDEX index_scope_index_type_idx on index_scope_fragment (type);
CREATE INDEX index_scope_index_parent_type_idx on index_scope_fragment (parent_type);