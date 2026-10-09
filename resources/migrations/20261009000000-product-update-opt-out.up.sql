-- Product update opt-out (ADR-0020). NULL means subscribed, so existing
-- users stay subscribed. The timestamp records when a user opted out.
ALTER TABLE users ADD COLUMN product_updates_opted_out_at TIMESTAMPTZ;
--;;
-- The credential in a user's unsubscribe link. It never expires, and the
-- default gives existing users a token too.
ALTER TABLE users ADD COLUMN unsubscribe_token UUID NOT NULL UNIQUE DEFAULT gen_random_uuid();
