PRAGMA foreign_keys = ON;

ALTER TABLE devices
ADD COLUMN signing_public_key TEXT;

ALTER TABLE devices
ADD COLUMN encryption_public_key TEXT;

CREATE TABLE IF NOT EXISTS device_registration_challenges (
    id TEXT PRIMARY KEY,
    challenge_hash TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_registration_challenges_expiry
ON device_registration_challenges(expires_at);
