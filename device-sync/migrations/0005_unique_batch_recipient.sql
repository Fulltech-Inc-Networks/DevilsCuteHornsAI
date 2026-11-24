CREATE UNIQUE INDEX IF NOT EXISTS idx_transfers_batch_recipient
ON transfers(batch_id, recipient_device_id)
WHERE batch_id IS NOT NULL;
