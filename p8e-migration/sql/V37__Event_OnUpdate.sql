DROP TRIGGER notify_event ON event;

CREATE TRIGGER notify_event
    AFTER INSERT OR UPDATE ON event
    FOR EACH ROW
    EXECUTE PROCEDURE notify_event();
