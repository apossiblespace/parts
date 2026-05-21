CREATE TABLE invitations (
  id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  email              TEXT NOT NULL UNIQUE,
  token              TEXT NOT NULL UNIQUE,
  is_founding_circle BOOLEAN NOT NULL DEFAULT true,
  invited_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  redeemed_at        TIMESTAMPTZ,
  revoked_at         TIMESTAMPTZ
);
--;;
-- `token` is UNIQUE, which already creates the btree index that the
-- /invite/:token lookup needs — no explicit CREATE INDEX required.
ALTER TABLE users ADD COLUMN is_founding_circle BOOLEAN NOT NULL DEFAULT false;
--;;
ALTER TABLE users ADD COLUMN paid_through_date DATE;
