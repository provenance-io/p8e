CREATE TABLE data_migration (
    name TEXT NOT NULL PRIMARY KEY,
    state JSONB NOT NULL,
    created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed TIMESTAMPTZ
)
