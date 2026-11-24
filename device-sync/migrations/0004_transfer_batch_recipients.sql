PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS transfer_batch_recipients (
    batch_id TEXT NOT NULL,
    recipient_device_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'delivered', 'expired')),
    transfer_id TEXT,
    created_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    PRIMARY KEY (batch_id, recipient_device_id),
    FOREIGN KEY (batch_id)
        REFERENCES transfer_batches(id) ON DELETE CASCADE,
    FOREIGN KEY (recipient_device_id)
        REFERENCES devices(id),
    CHECK (
        (status = 'pending' AND acknowledged_at IS NULL)
        OR
        (status = 'delivered' AND acknowledged_at IS NOT NULL)
        OR
        status = 'expired'
    )
);

CREATE INDEX IF NOT EXISTS idx_batch_recipients_status
ON transfer_batch_recipients(batch_id, status);

CREATE INDEX IF NOT EXISTS idx_batch_recipients_device
ON transfer_batch_recipients(recipient_device_id, status);
