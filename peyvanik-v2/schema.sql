
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS accounts(
 id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, username TEXT UNIQUE, display_name TEXT NOT NULL DEFAULT '',
 created_at INTEGER NOT NULL, verified_at INTEGER NOT NULL, disabled_at INTEGER,
 password_hash TEXT, password_salt TEXT, password_iterations INTEGER
);
CREATE TABLE IF NOT EXISTS sessions(
 id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 token_hash TEXT NOT NULL UNIQUE, device_name TEXT NOT NULL, created_at INTEGER NOT NULL,
 last_seen_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER,
 UNIQUE(account_id,id)
);
CREATE INDEX IF NOT EXISTS sessions_owner ON sessions(account_id,revoked_at);
CREATE TABLE IF NOT EXISTS auth_challenges(
 id TEXT PRIMARY KEY,email TEXT NOT NULL,account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
 code_hash TEXT NOT NULL,channel TEXT NOT NULL CHECK(channel IN ('email','device')),
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 consumed_at INTEGER, device_name TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS auth_challenges_email ON auth_challenges(email,created_at);
CREATE TABLE IF NOT EXISTS conversations(
 id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('direct','group'))
);
CREATE TABLE IF NOT EXISTS conversation_members(
 conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 joined_at INTEGER NOT NULL, PRIMARY KEY(conversation_id,account_id)
);
CREATE INDEX IF NOT EXISTS member_account ON conversation_members(account_id,conversation_id);
CREATE TABLE IF NOT EXISTS cloud_items(
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 kind TEXT NOT NULL CHECK(kind IN ('note','file')), name TEXT NOT NULL, body TEXT,
 object_key TEXT UNIQUE, mime TEXT, size INTEGER NOT NULL DEFAULT 0 CHECK(size>=0),
 created_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'ready' CHECK(state IN ('pending','ready','deleting')),
 CHECK((kind='note' AND object_key IS NULL AND body IS NOT NULL) OR (kind='file' AND object_key IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS cloud_owner_time ON cloud_items(owner_id,created_at DESC,id);
CREATE TABLE IF NOT EXISTS chat_messages(
 id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 sender_id TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, deleted_at INTEGER,
 FOREIGN KEY(conversation_id,sender_id) REFERENCES conversation_members(conversation_id,account_id)
);
CREATE INDEX IF NOT EXISTS message_conversation_time ON chat_messages(conversation_id,created_at DESC,id);
