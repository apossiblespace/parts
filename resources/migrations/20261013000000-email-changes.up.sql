-- Self-service email change (TASK-154.02). A row is a single-use link
-- emailed to the NEW address; opening it moves the account to that
-- address. Shaped like password_resets, but a table of its own, so one
-- kind of link can never be redeemed as the other. One hour, like a
-- reset: the link changes how an account holding clinical data signs in.
-- old_email is the address the account had when the change was confirmed.
-- It is kept for 30 days after the change (the sweep in
-- aps.parts.email-changes), so the owner can be helped if someone else
-- changed it.
CREATE TABLE email_changes (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  new_email TEXT NOT NULL,
  old_email TEXT,
  token TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '1 hour'),
  used_at TIMESTAMPTZ
);
--;;
CREATE INDEX email_changes_user ON email_changes (user_id);
