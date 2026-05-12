-- Split `systems` into identity-only + a bitemporal `system_metadata` table.
--
-- Why: `title` (and future sharing/permissions/client_id fields) need replay
-- history so the scrubber can show "what was the system named at session T"
-- and audit can answer "who could see this system at time T." Identity
-- (id, owner_id) doesn't change; metadata does. Splitting them keeps
-- parts.system_id FK-able to a unique systems.id while still capturing
-- metadata changes bitemporally.

CREATE TABLE system_metadata (
  id          UUID NOT NULL,
  system_id   UUID NOT NULL REFERENCES systems(id),
  title       TEXT NOT NULL,
  valid_at    TSTZRANGE NOT NULL,
  sys_period  TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id    UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
--;;
CREATE INDEX system_metadata_id_lookup     ON system_metadata USING gist (id, sys_period);
--;;
CREATE INDEX system_metadata_system_lookup ON system_metadata USING gist (system_id, valid_at, sys_period);
--;;
CREATE INDEX system_metadata_current       ON system_metadata (system_id) WHERE upper(sys_period) = 'infinity';
--;;

-- Audit trigger uses the same function as the other temporal tables.
CREATE TRIGGER system_metadata_audit
AFTER INSERT OR UPDATE OR DELETE ON system_metadata
FOR EACH ROW EXECUTE FUNCTION audit_log_change();
--;;

-- Backfill existing systems with an initial system_metadata row.
-- valid_at starts at the system's created_at; sys_period starts at now()
-- because that's when this row was first recorded in the DB.
INSERT INTO system_metadata (id, system_id, title, valid_at, sys_period, actor_id)
SELECT
  gen_random_uuid(),
  id,
  title,
  tstzrange(created_at, 'infinity', '[)'),
  tstzrange(now(),      'infinity', '[)'),
  actor_id
FROM systems
WHERE deleted_at IS NULL;
--;;

-- Drop the columns that moved out of systems.
ALTER TABLE systems DROP COLUMN title;
--;;
ALTER TABLE systems DROP COLUMN viewport_settings;
--;;

-- The deletion_role needs DELETE on system_metadata too (used by erasure).
GRANT DELETE, UPDATE, SELECT ON system_metadata TO deletion_role;
