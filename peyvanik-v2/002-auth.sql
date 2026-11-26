
CREATE TABLE IF NOT EXISTS google_identities(
 google_sub TEXT PRIMARY KEY, account_id TEXT NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
 totp_cipher TEXT NOT NULL, last_totp_step INTEGER NOT NULL DEFAULT -1
);
CREATE TABLE IF NOT EXISTS oidc_nonces(
 nonce_hash TEXT PRIMARY KEY, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER
);
CREATE TABLE IF NOT EXISTS totp_challenges(
 ticket_hash TEXT PRIMARY KEY, google_sub TEXT NOT NULL, email TEXT NOT NULL, display_name TEXT NOT NULL,
 device_name TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('setup','login')),
 account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE, pending_cipher TEXT,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS totp_challenges_sub ON totp_challenges(google_sub,created_at);
CREATE TABLE IF NOT EXISTS auth_rate(
 rate_key TEXT NOT NULL, bucket INTEGER NOT NULL, hits INTEGER NOT NULL,
 PRIMARY KEY(rate_key,bucket)
);
