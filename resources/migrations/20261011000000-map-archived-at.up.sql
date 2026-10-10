-- When the therapist archived the Map (CONTEXT.md, "Archived Map"). NULL
-- means the Map is active. An archived Map is read-only; the server refuses
-- every change to it until the therapist unarchives it.
ALTER TABLE maps ADD COLUMN archived_at TIMESTAMPTZ;
