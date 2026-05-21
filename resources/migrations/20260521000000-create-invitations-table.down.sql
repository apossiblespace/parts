ALTER TABLE users DROP COLUMN IF EXISTS paid_through_date;
--;;
ALTER TABLE users DROP COLUMN IF EXISTS is_founding_circle;
--;;
DROP TABLE IF EXISTS invitations;
