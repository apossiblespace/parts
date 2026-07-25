-- Finish the deletion_role least-privilege wiring (TASK-053).
--
-- The everyday app role loses DELETE on the temporal tables; the erasure
-- purge gains that capability only by SET LOCAL ROLE deletion_role inside
-- its transaction (db/erasure.clj). deletion_role gets every privilege the
-- purge path uses, so assuming the role can't make the purge fail.
--
-- DECISION (owner-vs-connection-role caveat): the app role OWNS these
-- tables, and an owner can re-GRANT itself DELETE — so this REVOKE is a
-- SPEED BUMP, not an airtight wall. It still stops every accidental or
-- injected DELETE running through normal query paths, which is the threat
-- this defends against. Separating table ownership from the connection
-- role would close the gap but is a much larger change (ownership
-- migration, migratus needs DDL as non-owner); deliberately not taken.
--
-- Role management split: CREATE ROLE / role membership need superuser and
-- happen in the provisioning scripts (bootstrap-prod.sh, add-instance.sh —
-- the app role holds NOCREATEROLE). This migration only GRANTs/REVOKEs on
-- tables the connecting role owns, which any owner may do. REVOKE ... FROM
-- CURRENT_USER targets whichever app role runs the migrations on this box
-- (parts on prod, parts_dev on staging, the dev's user locally — where a
-- superuser runs it, the revoke is recorded but superuser bypasses ACLs).

GRANT SELECT, DELETE ON parts, relationships, maps, map_metadata,
                       sessions, session_activations, invitations,
                       waitlist_signups, policy_acceptances TO deletion_role;
--;;
-- The audit trigger fires on the purge's own DELETEs and INSERTs rows as
-- the assumed role (which also draws from the id sequence); the scrub and
-- pseudonymization UPDATE it.
GRANT SELECT, INSERT, UPDATE ON audit_log TO deletion_role;
--;;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO deletion_role;
--;;
GRANT SELECT, UPDATE, DELETE ON users TO deletion_role;
--;;
REVOKE DELETE ON parts, relationships, maps, map_metadata, users
  FROM CURRENT_USER;
