-- The free trial is counted from created_at (ADR-0021), so every account
-- must have one. An account without it starts its trial now.
UPDATE users SET created_at = now() WHERE created_at IS NULL;
--;;
ALTER TABLE users ALTER COLUMN created_at SET NOT NULL;
