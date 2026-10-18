-- One row of fleet counts for each completed UTC day, for the growth
-- charts of the Operator console. Counts only: no row names a User.
-- Users and the three active counts are computed from users.created_at and
-- audit_log, so they can be back-filled. The other counts are a snapshot
-- of the moment the row was written, so they are NULL on back-filled days.
CREATE TABLE stats_daily (
  day             DATE PRIMARY KEY,
  users           INTEGER NOT NULL,
  active_day      INTEGER NOT NULL,
  active_7d       INTEGER NOT NULL,
  active_30d      INTEGER NOT NULL,
  subscribers     INTEGER,
  product_updates INTEGER,
  paid            INTEGER
);
