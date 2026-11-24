PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    public_key TEXT NOT NULL,
    key_algorithm TEXT NOT NULL DEFAULT 'ECDH-P256',
    token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_devices_active
ON devices(id, revoked_at);

CREATE TABLE IF NOT EXISTS pairing_sessions (
    id TEXT PRIMARY KEY,
    invitation_hash TEXT NOT NULL UNIQUE,
    initiator_device_id TEXT NOT NULL,
    initiator_ephemeral_public_key TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER,
    recipient_device_id TEXT,
    FOREIGN KEY (initiator_device_id)
        REFERENCES devices(id),
    FOREIGN KEY (recipient_device_id)
        REFERENCES devices(id)
);

CREATE INDEX IF NOT EXISTS idx_pairing_expiry
ON pairing_sessions(expires_at);

CREATE TABLE IF NOT EXISTS transfers (
    id TEXT NOT NULL,
    sender_device_id TEXT NOT NULL,
    recipient_device_id TEXT NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    payload_sha256 TEXT NOT NULL,
    ciphertext_size INTEGER NOT NULL,
    nonce TEXT NOT NULL,
    ephemeral_public_key TEXT NOT NULL,
    wrapped_key TEXT NOT NULL,
    key_nonce TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'delivered', 'expired')),
    PRIMARY KEY (id, recipient_device_id),
    FOREIGN KEY (sender_device_id)
        REFERENCES devices(id),
    FOREIGN KEY (recipient_device_id)
        REFERENCES devices(id),
    CHECK (ciphertext_size > 0)
);

CREATE INDEX IF NOT EXISTS idx_transfers_recipient
ON transfers(recipient_device_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_transfers_expiry
ON transfers(expires_at);

CREATE INDEX IF NOT EXISTS idx_transfers_sender
ON transfers(sender_device_id, created_at);
