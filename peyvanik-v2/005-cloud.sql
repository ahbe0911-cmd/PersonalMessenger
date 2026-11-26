
CREATE TABLE IF NOT EXISTS cloud_folders(
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 name TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(owner_id,name)
);
CREATE INDEX IF NOT EXISTS cloud_folders_owner ON cloud_folders(owner_id,created_at);
ALTER TABLE cloud_items ADD COLUMN folder_id TEXT REFERENCES cloud_folders(id) ON DELETE SET NULL;
ALTER TABLE cloud_items ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN (0,1));
ALTER TABLE cloud_items ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
UPDATE cloud_items SET updated_at=created_at WHERE updated_at=0;
UPDATE cloud_items SET size=length(CAST(body AS BLOB)) WHERE kind='note';
CREATE INDEX IF NOT EXISTS cloud_folder_items ON cloud_items(owner_id,folder_id,state);
CREATE TRIGGER IF NOT EXISTS cloud_folder_owner_insert BEFORE INSERT ON cloud_items
 WHEN NEW.folder_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cloud_folders WHERE id=NEW.folder_id AND owner_id=NEW.owner_id)
 BEGIN SELECT RAISE(ABORT,'FOLDER_OWNER'); END;
CREATE TRIGGER IF NOT EXISTS cloud_folder_owner_update BEFORE UPDATE OF folder_id,owner_id ON cloud_items
 WHEN NEW.folder_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM cloud_folders WHERE id=NEW.folder_id AND owner_id=NEW.owner_id)
 BEGIN SELECT RAISE(ABORT,'FOLDER_OWNER'); END;
CREATE TABLE IF NOT EXISTS cloud_rate(
 owner_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 bucket INTEGER NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY(owner_id,bucket)
);
