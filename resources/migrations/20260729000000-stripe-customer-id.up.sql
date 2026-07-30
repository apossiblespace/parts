-- Self-serve billing (TASK-046): the Stripe customer an account gets linked
-- to when its first Checkout completes. One customer per account; renewal
-- invoices are matched back to the account through this column.
ALTER TABLE users ADD COLUMN stripe_customer_id TEXT UNIQUE;
