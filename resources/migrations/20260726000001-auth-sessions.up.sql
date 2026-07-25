-- Server-side auth sessions (TASK-023). The browser cookie carries only an
-- opaque random UUID; the session data lives here — so any session can be
-- revoked server-side ("log out everywhere", lost device), and deleting a
-- user cascades their sessions away. Distinct from `sessions`, the clinical
-- timeline entity (ADR-0014).
--
-- `data` is EDN text (exact round-trip of ring's session map, including
-- namespaced keyword keys that JSONB would mangle). `expires_at` is the
-- ABSOLUTE 14-day bound (ADR-0007): writes refresh data, never the deadline.
-- `user_id` is NULL for anonymous sessions (the CSRF token pre-login).
CREATE TABLE auth_sessions (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  data TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);
--;;
CREATE INDEX auth_sessions_user ON auth_sessions (user_id);
--;;
CREATE INDEX auth_sessions_expires ON auth_sessions (expires_at);
