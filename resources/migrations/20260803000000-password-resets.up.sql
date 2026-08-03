-- Self-serve password reset (TASK-109). A row is a single-use magic-link
-- token emailed to an account holder; redeeming it sets a new password.
-- The TTL is deliberately short (1 hour, vs 180 days for invitations):
-- an invite creates an empty account, but a reset token grants access to
-- an existing account holding clinical data.
CREATE TABLE password_resets (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '1 hour'),
  used_at TIMESTAMPTZ
);
--;;
-- `token` is UNIQUE, which already creates the btree index the
-- /reset/:token lookup needs; this one serves the is-there-a-live-token
-- lookup by user on each reset request.
CREATE INDEX password_resets_user ON password_resets (user_id);
