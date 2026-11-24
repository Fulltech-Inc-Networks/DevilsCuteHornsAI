ALTER TABLE pairing_sessions
ADD COLUMN claimed_at INTEGER;

ALTER TABLE pairing_sessions
ADD COLUMN confirmation_expires_at INTEGER;

ALTER TABLE pairing_sessions
ADD COLUMN confirmed_at INTEGER;

ALTER TABLE pairing_sessions
ADD COLUMN recipient_ephemeral_public_key TEXT;
