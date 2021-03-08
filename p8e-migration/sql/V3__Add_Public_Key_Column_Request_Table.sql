ALTER TABLE index_event_pump_request ADD COLUMN public_key BYTEA NOT NULL UNIQUE;

CREATE INDEX index_event_pump_request_public_key_idx on index_event_pump_request (public_key);