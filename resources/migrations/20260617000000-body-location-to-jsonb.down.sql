-- Revert body_location to free text. JSONB renders back to its text form via
-- the cast; #>>'{}' would unwrap scalars, but a point is always an object, so
-- ::text is the faithful inverse.
ALTER TABLE parts
  ALTER COLUMN body_location TYPE TEXT USING body_location::text;
