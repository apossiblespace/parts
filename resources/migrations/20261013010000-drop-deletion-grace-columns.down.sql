ALTER TABLE users ADD COLUMN deletion_requested_at TIMESTAMPTZ;
--;;
ALTER TABLE users ADD COLUMN deletion_completed_at TIMESTAMPTZ;
