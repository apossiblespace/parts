-- Auth moved to a server-side session in an encrypted cookie (ADR-0007).
-- JWT refresh tokens are gone, so the table that tracked them is no longer
-- needed.
DROP TABLE IF EXISTS refresh_tokens;
