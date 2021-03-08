CREATE TABLE event_stream (
                              uuid UUID NOT NULL PRIMARY KEY,
                              last_block_height BIGINT NOT NULL DEFAULT 1,
                              created TIMESTAMPTZ NOT NULL,
                              updated TIMESTAMPTZ
);
