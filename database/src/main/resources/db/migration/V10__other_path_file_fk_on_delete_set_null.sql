-- Re-analysis deletes the metadata_entity / media_file_stream_entity rows that an NFO or
-- subtitle other_path_file_entity references. With the default NO ACTION these deletes hit a
-- foreign-key violation and the ANALYZE_DATA/*_FOUND handlers dead-letter. Null the reference
-- instead: the other_path_file row survives and is re-linked when the NFO/subtitle is re-parsed.
ALTER TABLE other_path_file_entity
    DROP CONSTRAINT other_path_file_entity_metadata_entity_id_fkey,
    ADD CONSTRAINT other_path_file_entity_metadata_entity_id_fkey
        FOREIGN KEY (metadata_entity_id) REFERENCES metadata_entity (id) ON DELETE SET NULL;

ALTER TABLE other_path_file_entity
    DROP CONSTRAINT other_path_file_entity_media_file_stream_entity_id_fkey,
    ADD CONSTRAINT other_path_file_entity_media_file_stream_entity_id_fkey
        FOREIGN KEY (media_file_stream_entity_id) REFERENCES media_file_stream_entity (id) ON DELETE SET NULL;
