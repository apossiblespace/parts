REVOKE ALL ON system_metadata FROM deletion_role;
--;;
ALTER TABLE systems ADD COLUMN title TEXT NOT NULL DEFAULT 'Untitled System';
--;;
ALTER TABLE systems ADD COLUMN viewport_settings TEXT;
--;;
-- Restore titles from the most-recent current metadata row.
UPDATE systems s
SET title = sm.title
FROM (
  SELECT DISTINCT ON (system_id) system_id, title
  FROM system_metadata
  WHERE upper(sys_period) = 'infinity' AND upper(valid_at) = 'infinity'
  ORDER BY system_id, lower(valid_at) DESC
) sm
WHERE s.id = sm.system_id;
--;;
DROP TRIGGER IF EXISTS system_metadata_audit ON system_metadata;
--;;
DROP TABLE IF EXISTS system_metadata;
