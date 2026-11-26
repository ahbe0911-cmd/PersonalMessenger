
CREATE TABLE IF NOT EXISTS direct_pairs(
 account_low TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 account_high TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 conversation_id TEXT NOT NULL UNIQUE REFERENCES conversations(id) ON DELETE CASCADE,
 PRIMARY KEY(account_low,account_high), CHECK(account_low<account_high)
);
CREATE TABLE IF NOT EXISTS user_blocks(
 blocker_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 blocked_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 created_at INTEGER NOT NULL,
 PRIMARY KEY(blocker_id,blocked_id), CHECK(blocker_id<>blocked_id)
);
CREATE INDEX IF NOT EXISTS blocks_reverse ON user_blocks(blocked_id,blocker_id);
CREATE UNIQUE INDEX IF NOT EXISTS account_username_ci ON accounts(lower(username)) WHERE username IS NOT NULL;
CREATE TABLE IF NOT EXISTS chat_events(
 seq INTEGER PRIMARY KEY AUTOINCREMENT,
 conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 message_id TEXT NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
 created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS chat_events_conversation ON chat_events(conversation_id,seq);
CREATE TRIGGER IF NOT EXISTS chat_message_created AFTER INSERT ON chat_messages BEGIN
 INSERT INTO chat_events(conversation_id,message_id,created_at) VALUES(NEW.conversation_id,NEW.id,NEW.created_at);
END;
CREATE TRIGGER IF NOT EXISTS chat_message_deleted AFTER UPDATE OF deleted_at ON chat_messages
 WHEN OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL BEGIN
 INSERT INTO chat_events(conversation_id,message_id,created_at) VALUES(NEW.conversation_id,NEW.id,NEW.deleted_at);
END;
CREATE TABLE IF NOT EXISTS chat_rate(
 owner_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 bucket INTEGER NOT NULL,
 hits INTEGER NOT NULL,
 PRIMARY KEY(owner_id,bucket)
);
