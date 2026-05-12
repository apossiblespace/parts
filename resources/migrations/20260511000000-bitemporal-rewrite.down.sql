DROP TRIGGER IF EXISTS relationships_audit ON relationships;
--;;
DROP TRIGGER IF EXISTS parts_audit ON parts;
--;;
DROP TRIGGER IF EXISTS systems_audit ON systems;
--;;
DROP FUNCTION IF EXISTS audit_log_change();
--;;
DROP TABLE IF EXISTS audit_log;
--;;
DROP TABLE IF EXISTS relationships;
--;;
DROP TABLE IF EXISTS parts;
--;;
DROP TABLE IF EXISTS systems;
--;;
DROP TYPE IF EXISTS relationship_type;
--;;
DROP TYPE IF EXISTS part_type;
--;;
DELETE FROM users WHERE id = '00000000-0000-0000-0000-000000000000';
--;;
ALTER TABLE users DROP COLUMN IF EXISTS deletion_completed_at;
--;;
ALTER TABLE users DROP COLUMN IF EXISTS deletion_requested_at;
--;;
-- Note: this down migration does NOT restore the previous (non-bitemporal)
-- systems/parts/relationships tables. The original migration was a complete
-- rip-and-replace per design. To roll back further, restore from backup or
-- drop the DB and re-run from migration 0.
