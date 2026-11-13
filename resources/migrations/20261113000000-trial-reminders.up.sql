-- The send log of the trial reminder emails. A row records that an account
-- was sent one kind of reminder, so that the job sends each kind only once.
-- The kind 'ending' is sent 7 days before the free trial ends. The kind
-- 'ended' is sent on the first read-only day.
CREATE TABLE trial_reminders (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('ending', 'ended')),
  sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, kind)
);
