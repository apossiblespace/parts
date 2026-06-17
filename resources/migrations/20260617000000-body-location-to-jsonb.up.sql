-- A Part's body_location becomes a structured point (a silhouette view plus
-- normalized coordinates) instead of free text (ADR-0013). Storing it as JSONB
-- keeps the audit_log's to_jsonb(NEW) capture readable as nested JSON rather
-- than an escaped string, and matches the project's only structured-at-rest
-- pattern. Pre-launch the column is NULL everywhere (it never had a UI), so the
-- USING cast is a no-op on data and the bitemporal history rewrites cleanly.
ALTER TABLE parts
  ALTER COLUMN body_location TYPE JSONB USING body_location::jsonb;
