
CREATE TABLE IF NOT EXISTS device_login_codes(
 ticket_hash TEXT PRIMARY KEY REFERENCES totp_challenges(ticket_hash) ON DELETE CASCADE,
 account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 code_cipher TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 denied_at INTEGER
);
CREATE INDEX IF NOT EXISTS device_login_owner ON device_login_codes(account_id,expires_at);
CREATE TABLE IF NOT EXISTS recovery_codes(
 account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 code_hash TEXT NOT NULL, created_at INTEGER NOT NULL, consumed_at INTEGER,
 PRIMARY KEY(account_id,code_hash)
);
