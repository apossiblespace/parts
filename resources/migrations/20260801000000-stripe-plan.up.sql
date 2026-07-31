-- Self-serve billing (TASK-046): which plan (monthly/yearly) the linked
-- subscription is on, written at checkout and kept current on plan
-- switches. A UI fact — it names the renewal amount on the account page —
-- never a billing input.
ALTER TABLE users ADD COLUMN stripe_plan TEXT;
