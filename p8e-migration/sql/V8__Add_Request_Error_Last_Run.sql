ALTER TABLE index_event_pump_request ADD COLUMN error_last_run BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN updated TIMESTAMPTZ DEFAULT NULL;