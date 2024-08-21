CREATE TABLE nodes (
  id TEXT PRIMARY KEY,
  system_id TEXT NOT NULL,
  label TEXT NOT NULL,
  category TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (system_id) REFERENCES systems (id)
);

CREATE TABLE node_relationships (
  id TEXT PRIMARY KEY,
  from_node_id TEXT NOT NULL,
  to_node_id TEXT NOT NULL,
  FOREIGN KEY (from_node_id) REFERENCES nodes (id),
  FOREIGN KEY (to_node_id) REFERENCES nodes (id)
);

CREATE TRIGGER update_nodes_timestamp
AFTER UPDATE ON nodes
FOR EACH ROW
BEGIN
  UPDATE nodes SET updated_at = CURRENT_TIMESTAMP WHERE id = OLD.id;
END;
