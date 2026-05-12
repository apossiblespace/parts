-- Defense-in-depth: a dedicated `deletion_role` for the right-to-erasure job.
-- After this migration, the role exists with DELETE rights on temporal tables.
-- Wiring the application to connect AS this role for purge operations (and
-- revoking DELETE from the regular app role) is a deploy-time configuration
-- step — the SQL infrastructure here is the prerequisite.
--
-- The role is NOLOGIN; it must be GRANTed to a login-able role at deploy time
-- (`GRANT deletion_role TO parts_app`) to be usable.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deletion_role') THEN
    CREATE ROLE deletion_role NOLOGIN;
  END IF;
END
$$;
--;;

GRANT DELETE ON parts         TO deletion_role;
--;;
GRANT DELETE ON relationships TO deletion_role;
--;;
GRANT DELETE ON systems       TO deletion_role;
--;;
GRANT DELETE ON users         TO deletion_role;
--;;
GRANT DELETE ON audit_log     TO deletion_role;
--;;
GRANT UPDATE ON audit_log     TO deletion_role;
--;;
GRANT UPDATE ON users         TO deletion_role;
--;;
GRANT SELECT ON users         TO deletion_role;
--;;
GRANT SELECT ON systems       TO deletion_role;
