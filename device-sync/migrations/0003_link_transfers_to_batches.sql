PRAGMA foreign_keys = ON;

ALTER TABLE transfers
ADD COLUMN batch_id TEXT
REFERENCES transfer_batches(id);

CREATE INDEX IF NOT EXISTS idx_transfers_batch
ON transfers(batch_id, status);
