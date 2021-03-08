DELETE FROM event WHERE 1 = 1;

ALTER TABLE event ADD COLUMN event TEXT NOT NULL;

CREATE OR REPLACE FUNCTION notify_event()
    RETURNS trigger AS $$
DECLARE
BEGIN
    PERFORM pg_notify(lower(NEW.event), json_build_object(
            'uuid', NEW.uuid,
            'payload', encode(NEW.payload, 'base64'),
            'event', NEW.event,
            'status', NEW.status,
            'created', NEW.created,
            'updated', NEW.updated)::text
                );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER notify_event ON event;

CREATE TRIGGER notify_event
    AFTER INSERT ON event
    FOR EACH ROW
EXECUTE PROCEDURE notify_event();