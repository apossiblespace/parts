-- Bitemporal rewrite of parts, relationships, systems.
-- PG16-compatible syntax (EXCLUDE USING gist); upgrade to PG18 WITHOUT OVERLAPS later.

DROP TABLE IF EXISTS relationships;
--;;
DROP TABLE IF EXISTS parts;
--;;
DROP TABLE IF EXISTS systems;
--;;

CREATE EXTENSION IF NOT EXISTS btree_gist;
--;;

ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ;
--;;
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_completed_at TIMESTAMPTZ;
--;;

INSERT INTO users (id, email, username, display_name, password_hash, role)
VALUES (
  '00000000-0000-0000-0000-000000000000',
  'deleted@aps.local',
  '__deleted__',
  'Deleted user',
  '!',
  'therapist'
)
ON CONFLICT (id) DO NOTHING;
--;;

CREATE TYPE part_type AS ENUM ('manager', 'firefighter', 'exile', 'unknown');
--;;
CREATE TYPE relationship_type AS ENUM ('protective', 'polarization', 'alliance', 'burden', 'blended', 'unknown');
--;;

CREATE TABLE systems (
  id                UUID NOT NULL DEFAULT uuid_generate_v4(),
  title             TEXT NOT NULL DEFAULT 'Untitled System',
  owner_id          UUID NOT NULL REFERENCES users(id),
  viewport_settings TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ,
  actor_id          UUID NOT NULL REFERENCES users(id),
  PRIMARY KEY (id)
);
--;;
CREATE INDEX systems_owner ON systems (owner_id) WHERE deleted_at IS NULL;
--;;

CREATE TABLE parts (
  id            UUID NOT NULL,
  system_id     UUID NOT NULL REFERENCES systems(id),
  type          part_type NOT NULL,
  label         TEXT NOT NULL,
  description   TEXT,
  position_x    INTEGER NOT NULL,
  position_y    INTEGER NOT NULL,
  width         INTEGER NOT NULL DEFAULT 100,
  height        INTEGER NOT NULL DEFAULT 100,
  body_location TEXT,
  notes         TEXT,
  valid_at      TSTZRANGE NOT NULL,
  sys_period    TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id      UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
--;;
CREATE INDEX parts_id_lookup     ON parts USING gist (id, sys_period);
--;;
CREATE INDEX parts_system_lookup ON parts USING gist (system_id, valid_at, sys_period);
--;;
CREATE INDEX parts_current       ON parts (id) WHERE upper(sys_period) = 'infinity';
--;;

CREATE TABLE relationships (
  id         UUID NOT NULL,
  system_id  UUID NOT NULL REFERENCES systems(id),
  source_id  UUID NOT NULL,
  target_id  UUID NOT NULL,
  type       relationship_type NOT NULL,
  notes      TEXT,
  valid_at   TSTZRANGE NOT NULL,
  sys_period TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id   UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
--;;
-- Note: source_id / target_id reference parts.id but cannot use a regular FK
-- because parts.id is non-unique (history rows share id). Application-layer
-- bitemporal/insert! ensures the referenced part exists at the valid-time.
CREATE INDEX relationships_id_lookup     ON relationships USING gist (id, sys_period);
--;;
CREATE INDEX relationships_system_lookup ON relationships USING gist (system_id, valid_at, sys_period);
--;;
CREATE INDEX relationships_source        ON relationships USING gist (source_id, valid_at, sys_period);
--;;
CREATE INDEX relationships_target        ON relationships USING gist (target_id, valid_at, sys_period);
--;;
CREATE INDEX relationships_current       ON relationships (id) WHERE upper(sys_period) = 'infinity';
--;;

CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    UUID NOT NULL REFERENCES users(id),
  table_name  TEXT NOT NULL,
  operation   CHAR(1) NOT NULL CHECK (operation IN ('I','U','D')),
  row_pk      JSONB NOT NULL,
  before_row  JSONB,
  after_row   JSONB
);
--;;
CREATE INDEX audit_log_actor  ON audit_log (actor_id);
--;;
CREATE INDEX audit_log_target ON audit_log (table_name, (row_pk->>'id'));
--;;
CREATE INDEX audit_log_when   ON audit_log (occurred_at);
--;;

CREATE OR REPLACE FUNCTION audit_log_change() RETURNS TRIGGER AS $$
DECLARE
  actor UUID;
  row_id UUID;
BEGIN
  BEGIN
    actor := current_setting('aps.actor_id', true)::UUID;
  EXCEPTION WHEN OTHERS THEN
    actor := NULL;
  END;

  IF actor IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      actor := OLD.actor_id;
    ELSE
      actor := NEW.actor_id;
    END IF;
  END IF;

  IF actor IS NULL THEN
    actor := '00000000-0000-0000-0000-000000000000'::UUID;
  END IF;

  IF TG_OP = 'DELETE' THEN
    row_id := OLD.id;
  ELSE
    row_id := NEW.id;
  END IF;

  INSERT INTO audit_log (actor_id, table_name, operation, row_pk, before_row, after_row)
  VALUES (
    actor,
    TG_TABLE_NAME,
    CASE TG_OP WHEN 'INSERT' THEN 'I' WHEN 'UPDATE' THEN 'U' WHEN 'DELETE' THEN 'D' END,
    jsonb_build_object('id', row_id),
    CASE TG_OP WHEN 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
    CASE TG_OP WHEN 'DELETE' THEN NULL ELSE to_jsonb(NEW) END
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
--;;

CREATE TRIGGER systems_audit
AFTER INSERT OR UPDATE OR DELETE ON systems
FOR EACH ROW EXECUTE FUNCTION audit_log_change();
--;;
CREATE TRIGGER parts_audit
AFTER INSERT OR UPDATE OR DELETE ON parts
FOR EACH ROW EXECUTE FUNCTION audit_log_change();
--;;
CREATE TRIGGER relationships_audit
AFTER INSERT OR UPDATE OR DELETE ON relationships
FOR EACH ROW EXECUTE FUNCTION audit_log_change();
