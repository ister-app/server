ALTER TABLE stream_token_entity ALTER COLUMN user_entity_id DROP NOT NULL;
ALTER TABLE stream_token_entity ADD COLUMN download boolean NOT NULL DEFAULT false;
ALTER TABLE stream_token_entity ADD COLUMN upload   boolean NOT NULL DEFAULT false;
