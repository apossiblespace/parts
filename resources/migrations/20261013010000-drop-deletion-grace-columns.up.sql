-- Account deletion is immediate (TASK-154.05): the 30-day grace flow these
-- columns served was never connected, and erasure deletes the users row.
ALTER TABLE users DROP COLUMN IF EXISTS deletion_requested_at;
--;;
ALTER TABLE users DROP COLUMN IF EXISTS deletion_completed_at;
