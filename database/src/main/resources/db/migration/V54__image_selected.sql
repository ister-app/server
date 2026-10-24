-- An admin's choice of artwork: at most one image per item and type is "selected" and wins over
-- the automatic pick (local file first, then lowest id). The flag lives on the row itself; a forced
-- metadata refresh deletes the other rows of the item and leaves a selected one standing.
ALTER TABLE image_entity ADD COLUMN selected BOOLEAN NOT NULL DEFAULT FALSE;
