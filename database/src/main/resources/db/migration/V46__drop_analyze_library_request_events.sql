-- The ANALYZE_LIBRARY_REQUEST event type was replaced by METADATA_BACKFILL_REQUESTED. Nothing in
-- the code reads server_event_entity, but its event_type column stores the enum name as a string;
-- drop any stale rows defensively so a future reader can never hit an unmappable value.
DELETE FROM server_event_entity WHERE event_type = 'ANALYZE_LIBRARY_REQUEST';
