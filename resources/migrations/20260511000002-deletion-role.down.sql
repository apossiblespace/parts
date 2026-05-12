REVOKE ALL ON parts         FROM deletion_role;
--;;
REVOKE ALL ON relationships FROM deletion_role;
--;;
REVOKE ALL ON systems       FROM deletion_role;
--;;
REVOKE ALL ON users         FROM deletion_role;
--;;
REVOKE ALL ON audit_log     FROM deletion_role;
--;;
-- DROP the role only if no other database is using it.
-- Postgres roles are cluster-global; a drop here would affect every DB sharing
-- the cluster. Comment this out if you have other databases that grant the
-- role.
DROP ROLE IF EXISTS deletion_role;
