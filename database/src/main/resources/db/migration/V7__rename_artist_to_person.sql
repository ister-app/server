ALTER TABLE artist_entity RENAME TO person_entity;
ALTER TABLE album_entity RENAME COLUMN artist_entity_id TO person_entity_id;
ALTER TABLE track_entity RENAME COLUMN artist_entity_id TO person_entity_id;
ALTER TABLE image_entity RENAME COLUMN artist_entity_id TO person_entity_id;
ALTER TABLE metadata_entity RENAME COLUMN artist_entity_id TO person_entity_id;
