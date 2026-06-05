CREATE TABLE policy_acceptances (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID NOT NULL REFERENCES users(id),
  document    TEXT NOT NULL,
  version     TEXT NOT NULL,
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, document, version)
);
