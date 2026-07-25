-- Invitation tokens get a TTL: single-use was the only bound, so an old
-- unredeemed magic link stayed a live bearer credential forever. Existing
-- pending invitations get a fresh 180 days from this migration rather than
-- being killed retroactively.
ALTER TABLE invitations
  ADD COLUMN expires_at TIMESTAMPTZ NOT NULL
  DEFAULT (now() + interval '180 days');
