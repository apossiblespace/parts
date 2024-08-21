CREATE TABLE systems (
  id TEXT PRIMARY KEY,
  therapist_id TEXT NOT NULL,
  client_id TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (therapist_id) REFERENCES users (id),
  FOREIGN KEY (client_id) REFERENCES users (id)
);

CREATE TRIGGER update_systems_timestamp
AFTER UPDATE ON systems
FOR EACH ROW
BEGIN
  UPDATE systems SET updated_at = CURRENT_TIMESTAMP WHERE id = OLD.id;
END;
