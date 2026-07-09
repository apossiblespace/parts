-- Sessions: named markers on a Map's bitemporal timeline (ADR-0014).
-- Deliberately NON-temporal: a Session's identity and anchor never change,
-- and no history of trigger wording is kept. Membership ("which Parts first
-- appeared in Session N") is derived from parts/relationships valid_at
-- ranges — no session_id column exists on content tables. The audit_log
-- trigger only fires on temporal tables, so Session writes audit explicitly
-- (aps.parts.db.audit), and erasure/export must cover these tables
-- deliberately (ADR-0014; TASK-073.06).

CREATE TABLE sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  map_id UUID NOT NULL REFERENCES maps(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  trigger TEXT,
  anchor_valid_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (map_id, ordinal)
);
--;;
CREATE INDEX sessions_map_anchor ON sessions (map_id, anchor_valid_at);
--;;
-- Activation is a link row, not a scalar column, so growing from one
-- activated Part to several (the post-launch Trigger-node model) needs no
-- table change; one-per-Session is enforced at the API layer for launch.
-- part_id has no FK: parts.id is non-unique across bitemporal history —
-- existence is enforced in the entity layer, like relationships' endpoints.
CREATE TABLE session_activations (
  session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  part_id UUID NOT NULL,
  PRIMARY KEY (session_id, part_id)
);
