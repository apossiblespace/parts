GRANT DELETE ON parts, relationships, maps, map_metadata, users
  TO CURRENT_USER;
--;;
REVOKE SELECT, DELETE ON sessions, session_activations, invitations,
                         waitlist_signups, policy_acceptances, map_metadata
  FROM deletion_role;
--;;
REVOKE SELECT ON parts, relationships FROM deletion_role;
--;;
REVOKE INSERT ON audit_log FROM deletion_role;
--;;
REVOKE USAGE ON SEQUENCE audit_log_id_seq FROM deletion_role;
