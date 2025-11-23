CREATE TABLE systems (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  title TEXT NOT NULL DEFAULT 'Untitled System',
  owner_id UUID NOT NULL,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  last_modified TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  viewport_settings TEXT,
  FOREIGN KEY (owner_id) REFERENCES users(id)
);
--;;

CREATE TABLE parts (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  system_id UUID NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('manager', 'firefighter', 'exile', 'unknown')),
  label TEXT NOT NULL,
  description TEXT,
  position_x INTEGER NOT NULL,
  position_y INTEGER NOT NULL,
  width INTEGER NOT NULL DEFAULT 100,
  height INTEGER NOT NULL DEFAULT 100,
  body_location TEXT,
  notes TEXT,
  FOREIGN KEY (system_id) REFERENCES systems(id)
);
--;;

CREATE INDEX idx_parts_system_id ON parts(system_id);
--;;

CREATE TABLE relationships (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  system_id UUID NOT NULL,
  source_id UUID NOT NULL,
  target_id UUID NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('protective', 'polarization', 'alliance', 'burden', 'blended', 'unknown')),
  notes TEXT,
  FOREIGN KEY (system_id) REFERENCES systems(id),
  FOREIGN KEY (source_id) REFERENCES parts(id),
  FOREIGN KEY (target_id) REFERENCES parts(id)
);
--;;

CREATE INDEX idx_relationships_system_id ON relationships(system_id);
--;;
CREATE INDEX idx_relationships_source_id ON relationships(source_id);
--;;
CREATE INDEX idx_relationships_target_id ON relationships(target_id);
