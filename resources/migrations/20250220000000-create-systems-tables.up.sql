CREATE TABLE systems (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT 'Untitled System',
    owner_id TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    viewport_settings TEXT,  -- JSON string containing zoom and position
    FOREIGN KEY (owner_id) REFERENCES users(id)
);
--;;
CREATE TABLE nodes (
    id TEXT PRIMARY KEY,
    system_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('manager', 'firefighter', 'exile', 'unknown')),
    label TEXT NOT NULL,
    description TEXT,
    position_x INTEGER NOT NULL,
    position_y INTEGER NOT NULL,
    width INTEGER NOT NULL DEFAULT 100,
    height INTEGER NOT NULL DEFAULT 100,
    body_location TEXT,
    FOREIGN KEY (system_id) REFERENCES systems(id) ON DELETE CASCADE
);
--;;
CREATE TABLE edges (
    id TEXT PRIMARY KEY,
    system_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    target_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('protective', 'polarization', 'alliance', 'burden', 'blended')),
    notes TEXT,
    FOREIGN KEY (system_id) REFERENCES systems(id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_id) REFERENCES nodes(id) ON DELETE CASCADE
);
--;;
CREATE INDEX idx_nodes_system_id ON nodes(system_id);
--;;
CREATE INDEX idx_edges_system_id ON edges(system_id);
--;;
CREATE INDEX idx_edges_source_id ON edges(source_id);
--;;
CREATE INDEX idx_edges_target_id ON edges(target_id);
