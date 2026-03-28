DELETE FROM other_path_file_entity;
ALTER TABLE other_path_file_entity ADD COLUMN metadata_entity_id UUID REFERENCES metadata_entity(id);
ALTER TABLE other_path_file_entity ADD COLUMN media_file_stream_entity_id UUID REFERENCES media_file_stream_entity(id);
