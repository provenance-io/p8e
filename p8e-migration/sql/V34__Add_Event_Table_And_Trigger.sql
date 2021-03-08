CREATE TABLE event (
    uuid UUID NOT NULL PRIMARY KEY,
    payload BYTEA NOT NULL,
    status TEXT NOT NULL,
    created TIMESTAMPTZ NOT NULL,
    updated TIMESTAMPTZ DEFAULT NULL
);

CREATE INDEX idx_event_status on event (status);

CREATE OR REPLACE FUNCTION notify_event()
    RETURNS trigger AS $$
DECLARE
BEGIN
    PERFORM pg_notify('event', json_build_object(
        'uuid', NEW.uuid,
        'payload', encode(NEW.payload, 'base64'),
        'status', NEW.status,
        'created', NEW.created,
        'updated', NEW.updated)::text
        );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER notify_event
    AFTER INSERT OR UPDATE ON event
    FOR EACH ROW
EXECUTE PROCEDURE notify_event();

CREATE TABLE affiliate_connection (
  uuid UUID NOT NULL PRIMARY KEY,
  affiliate_uuid UUID NOT NULL REFERENCES affiliate (affiliate_uuid),
  certificate_uuid UUID NOT NULL,
  classname TEXT NOT NULL,
  connection_status TEXT NOT NULL DEFAULT 'NOT_CONNECTED',
  last_heartbeat TIMESTAMPTZ NOT NULL
);