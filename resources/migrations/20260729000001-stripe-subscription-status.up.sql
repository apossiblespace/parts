-- Self-serve billing (TASK-046): the linked account's live Stripe
-- subscription status, maintained by the subscription lifecycle webhooks.
-- Drives the Account page's subscribe-vs-manage choice and the
-- double-subscribe guard. Never a billing input: paid_through_date remains
-- the only billing fact.
ALTER TABLE users ADD COLUMN stripe_subscription_status TEXT;
