-- Conversation entries (ADR-0018): one thing said or done in the
-- conversation with one Part. Bitemporal, same shape as parts /
-- relationships (ADR-0001). part_id has no FK: parts.id is non-unique
-- across history rows; the entity layer checks the Part is live in the
-- same Map before insert.
CREATE TABLE conversation_entries (
  id         UUID NOT NULL,
  map_id     UUID NOT NULL REFERENCES maps(id),
  part_id    UUID NOT NULL,
  speaker    TEXT NOT NULL CHECK (speaker IN ('self', 'part', 'therapist')),
  text       TEXT NOT NULL,
  valid_at   TSTZRANGE NOT NULL,
  sys_period TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id   UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
--;;
CREATE INDEX conversation_entries_id_lookup  ON conversation_entries USING gist (id, sys_period);
--;;
CREATE INDEX conversation_entries_map_lookup ON conversation_entries USING gist (map_id, valid_at, sys_period);
--;;
CREATE INDEX conversation_entries_part       ON conversation_entries USING gist (part_id, valid_at, sys_period);
--;;
CREATE INDEX conversation_entries_current    ON conversation_entries (id) WHERE upper(sys_period) = 'infinity';
--;;
CREATE TRIGGER conversation_entries_audit
AFTER INSERT OR UPDATE OR DELETE ON conversation_entries
FOR EACH ROW EXECUTE FUNCTION audit_log_change();
--;;
-- Erasure least-privilege (20260726000000): the purge deletes these rows
-- as deletion_role; the everyday app role holds no DELETE.
GRANT SELECT, DELETE ON conversation_entries TO deletion_role;
--;;
REVOKE DELETE ON conversation_entries FROM CURRENT_USER;
