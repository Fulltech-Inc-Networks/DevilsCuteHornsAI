PRAGMA foreign_keys = ON;

-- A relationship exists only after both devices complete pairing.
-- Store device IDs in lexicographical order to prevent duplicate pairs.
CREATE TABLE IF NOT EXISTS device_pairs (
    id TEXT PRIMARY KEY,
    device_a_id TEXT NOT NULL,
    device_b_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER,
    FOREIGN KEY (device_a_id) REFERENCES devices(id),
    FOREIGN KEY (device_b_id) REFERENCES devices(id),
    CHECK (device_a_id < device_b_id),
    UNIQUE (device_a_id, device_b_id)
);

CREATE INDEX IF NOT EXISTS idx_device_pairs_active_a
ON device_pairs(device_a_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_device_pairs_active_b
ON device_pairs(device_b_id, revoked_at);

-- One batch groups the individual encrypted copies sent to recipients.
CREATE TABLE IF NOT EXISTS transfer_batches (
    id TEXT PRIMARY KEY,
    sender_device_id TEXT NOT NULL,
    expected_recipients INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'completed', 'expired')),
    completed_at INTEGER,
    cleanup_after INTEGER,
    FOREIGN KEY (sender_device_id) REFERENCES devices(id),
    CHECK (expires_at > created_at),
    CHECK (expected_recipients > 0 AND expected_recipients <= 20)
);

CREATE INDEX IF NOT EXISTS idx_transfer_batches_expiry
ON transfer_batches(status, expires_at);

CREATE INDEX IF NOT EXISTS idx_transfer_batches_cleanup
ON transfer_batches(status, cleanup_after);
