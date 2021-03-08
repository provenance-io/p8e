CREATE TABLE transaction_status (
    transaction_hash TEXT NOT NULL PRIMARY KEY,
    execution_uuids jsonb NOT NULL,
    status VARCHAR(20) NOT NULL,
    raw_log TEXT,
    created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
)