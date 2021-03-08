CREATE OR REPLACE FUNCTION notify_event()
    RETURNS trigger AS $$
DECLARE
BEGIN
    PERFORM pg_notify(lower(NEW.event), json_build_object(
            'uuid', NEW.uuid,
            'event', NEW.event,
            'status', NEW.status,
            'created', NEW.created,
            'updated', NEW.updated)::text
                );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;