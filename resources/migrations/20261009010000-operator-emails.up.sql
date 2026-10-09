-- Emails the operator sends to Users from the Operator console (ADR-0020).
CREATE TABLE operator_emails (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind         TEXT NOT NULL CHECK (kind IN ('product-update', 'service-notice')),
  subject      TEXT NOT NULL,
  body         TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
--;;
-- One row per recipient, written after the relay accepts the message or
-- with the error when it refuses. The rows are the evidence that a User was
-- notified, and a resumed send skips Users who already have a sent row.
CREATE TABLE operator_email_deliveries (
  email_id UUID NOT NULL REFERENCES operator_emails(id),
  user_id  UUID NOT NULL REFERENCES users(id),
  sent_at  TIMESTAMPTZ,
  error    TEXT,
  PRIMARY KEY (email_id, user_id)
);
--;;
-- Erasure deletes a User's delivery rows under deletion_role.
GRANT SELECT, DELETE ON operator_email_deliveries TO deletion_role;
